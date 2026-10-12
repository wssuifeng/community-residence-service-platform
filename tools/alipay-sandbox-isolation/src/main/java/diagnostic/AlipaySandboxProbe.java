package diagnostic;

import com.alipay.api.AlipayClient;
import com.alipay.api.AlipayConfig;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayTradePagePayModel;
import com.alipay.api.domain.AlipayTradeQueryModel;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 独立沙箱诊断：原样输出官方 SDK 表单，不访问业务系统或数据库。 */
public final class AlipaySandboxProbe {
    public static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    private static final String AMOUNT = "0.01";

    record Credentials(String appId, String privateKey, String publicKey, String gateway) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }

    static Credentials credentials(Map<String, String> env) {
        String gateway = env.getOrDefault("ALIPAY_PROBE_GATEWAY", SANDBOX_GATEWAY);
        if (!SANDBOX_GATEWAY.equals(gateway)) {
            throw new IllegalArgumentException("Only the documented HTTPS sandbox gateway is permitted");
        }
        String appId = required(env, "ALIPAY_PROBE_APP_ID");
        if (!appId.matches("[0-9]{16}")) throw new IllegalArgumentException("Invalid sandbox APPID format");
        return new Credentials(appId, required(env, "ALIPAY_PROBE_PRIVATE_KEY"),
                required(env, "ALIPAY_PROBE_PUBLIC_KEY"), gateway);
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value.trim();
    }

    static AlipayClient client(Credentials credentials) throws Exception {
        AlipayConfig config = new AlipayConfig();
        config.setServerUrl(credentials.gateway());
        config.setAppId(credentials.appId());
        config.setPrivateKey(credentials.privateKey());
        config.setAlipayPublicKey(credentials.publicKey());
        config.setFormat("json");
        config.setCharset("UTF-8");
        config.setSignType("RSA2");
        return new DefaultAlipayClient(config);
    }

    static String newOrderNo() {
        return "ISO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
    }

    static boolean isDiagnosticOrder(String no) {
        return no != null && no.matches("ISO[A-Z0-9]{10,40}");
    }

    static AlipayTradePagePayRequest payRequest(String orderNo) {
        if (!isDiagnosticOrder(orderNo)) throw new IllegalArgumentException("Diagnostic orders only");
        AlipayTradePagePayModel model = new AlipayTradePagePayModel();
        model.setOutTradeNo(orderNo);
        model.setTotalAmount(AMOUNT);
        model.setSubject("Alipay sandbox isolated test");
        model.setProductCode("FAST_INSTANT_TRADE_PAY");
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setBizModel(model);
        return request;
    }

    private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + ";charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }

    private static String json(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\"";
    }

    public static void main(String[] args) {
        try {
            int port = args.length == 0 ? 8095 : Integer.parseInt(args[0]);
            AlipayClient sdk = client(credentials(System.getenv()));
            Set<String> issuedOrders = ConcurrentHashMap.newKeySet();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
            server.createContext("/", exchange -> {
                try {
                    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                            || !"GET".equals(exchange.getRequestMethod())) {
                        respond(exchange, 405, "text/plain", "Local GET requests only");
                        return;
                    }
                    String route = exchange.getRequestURI().getPath();
                    if ("/".equals(route)) {
                        respond(exchange, 200, "text/html", "<!doctype html><meta charset='UTF-8'><title>Independent Alipay sandbox probe</title>"
                                + "<h1>Independent Alipay sandbox probe</h1><p>Sandbox only; fixed amount 0.01; each request uses a fresh ISO order.</p>"
                                + "<a href='/pay/post'>Official SDK native POST form</a><br><a href='/pay/get'>Official SDK complete GET</a>");
                    } else if ("/health".equals(route)) {
                        respond(exchange, 200, "application/json", "{\"ready\":true,\"sandboxOnly\":true,\"amount\":\"0.01\",\"businessDependencies\":false}");
                    } else if ("/pay/post".equals(route) || "/pay/get".equals(route)) {
                        String order = newOrderNo();
                        issuedOrders.add(order);
                        exchange.getResponseHeaders().set("X-Probe-Order", order);
                        AlipayTradePagePayRequest request = payRequest(order);
                        if ("/pay/post".equals(route)) {
                            respond(exchange, 200, "text/html", sdk.pageExecute(request).getBody());
                        } else {
                            exchange.getResponseHeaders().set("Location", sdk.pageExecute(request, "GET").getBody());
                            exchange.getResponseHeaders().set("Cache-Control", "no-store");
                            exchange.sendResponseHeaders(302, -1);
                        }
                    } else if ("/query-control".equals(route) || "/query".equals(route)) {
                        String order = newOrderNo();
                        if ("/query".equals(route)) {
                            String query = exchange.getRequestURI().getRawQuery();
                            if (query == null || !query.startsWith("order=")) throw new IllegalArgumentException("Missing order");
                            order = URLDecoder.decode(query.substring(6), StandardCharsets.UTF_8);
                            if (!isDiagnosticOrder(order) || !issuedOrders.contains(order)) throw new IllegalArgumentException("Unknown diagnostic order");
                        }
                        AlipayTradeQueryModel model = new AlipayTradeQueryModel();
                        model.setOutTradeNo(order);
                        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
                        request.setBizModel(model);
                        var result = sdk.execute(request);
                        respond(exchange, 200, "application/json", "{\"code\":" + json(result.getCode())
                                + ",\"subCode\":" + json(result.getSubCode()) + ",\"tradeStatus\":" + json(result.getTradeStatus())
                                + ",\"sdkResponseParsed\":true,\"responseHasSign\":" + result.getBody().contains("\"sign\"") + "}");
                    } else {
                        respond(exchange, 404, "text/plain", "Not found");
                    }
                } catch (Exception error) {
                    respond(exchange, 500, "application/json", "{\"errorType\":" + json(error.getClass().getSimpleName()) + "}");
                } finally { exchange.close(); }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            server.start();
            System.out.println("{\"event\":\"ready\",\"port\":" + server.getAddress().getPort() + "}");
        } catch (Exception error) {
            System.err.println("Probe startup failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
