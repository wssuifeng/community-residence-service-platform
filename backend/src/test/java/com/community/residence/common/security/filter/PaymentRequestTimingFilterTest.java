package com.community.residence.common.security.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentRequestTimingFilterTest {
    private final PaymentRequestTimingFilter filter = new PaymentRequestTimingFilter();

    @Test
    void paymentPostPreservesBodyAndAddsApplicationTimingWithoutCallingChannel() throws Exception {
        for (String purpose : new String[]{"first", "rent", "renew"}) {
            var request = new MockHttpServletRequest("POST", "/api/v1/leases/12/" + purpose + "-payments");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                res.setCharacterEncoding("UTF-8");
                res.setContentType("application/json");
                res.getWriter().write("{\"result\":\"本地测试\"}");
            });
            assertThat(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo("{\"result\":\"本地测试\"}");
            assertThat(response.getHeader("Server-Timing")).matches("payment_app;dur=[0-9]+\\.[0-9]");
        }
    }

    @Test
    void unrelatedRequestsAreNotWrappedOrTimed() throws Exception {
        for (String[] input : new String[][]{{"GET", "/api/v1/leases/12/rent-payments"},
                {"POST", "/api/v1/notices"}, {"POST", "/api/v1/payments/12/query"}}) {
            var request = new MockHttpServletRequest(input[0], input[1]);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> assertThat(res).isSameAs(response));
            assertThat(response.getHeader("Server-Timing")).isNull();
        }
    }

    @Test
    void rejectedApplicationResponseKeepsStatusAndBody() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/leases/12/rent-payments");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setStatus(409);
            res.getWriter().write("conflict");
        });
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).isEqualTo("conflict");
        assertThat(response.getHeader("Server-Timing")).startsWith("payment_app;dur=");
    }
}
