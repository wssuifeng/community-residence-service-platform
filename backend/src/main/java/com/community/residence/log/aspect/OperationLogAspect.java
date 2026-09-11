package com.community.residence.log.aspect;

import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.constant.RoleConstants;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.log.annotation.OperationLog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作留痕切面（R47 关键变更日志，DEF-014）：
 * 拦截 @OperationLog 标注的 Service 方法，成功返回后异步语义外同步写
 * sys_operation_log（写入失败仅 warn 不回滚业务）。
 * 操作人取安全上下文（JwtAuthenticationFilter 已填充），未认证（定时任务
 * 内部调用）时 operatorId 为空、operatorType 记 SYSTEM。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final SysOperationLogMapper operationLogMapper;

    private final SpelExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer paramNameDiscoverer = new DefaultParameterNameDiscoverer();

    @AfterReturning(pointcut = "@annotation(operationLogAnnotation)")
    public void recordOperation(JoinPoint joinPoint, OperationLog operationLogAnnotation) {
        try {
            SysOperationLog entry = new SysOperationLog();
            UserContext user = SecurityUtils.getUser();
            entry.setOperatorId(user != null ? user.getUserId() : null);
            entry.setOperatorType(user != null ? user.getRole() : "SYSTEM");
            entry.setCommunityId(resolveCommunityId(joinPoint, operationLogAnnotation, user));
            entry.setOperationType(operationLogAnnotation.operationType());
            entry.setTargetType(operationLogAnnotation.targetType());
            entry.setTargetId(resolveLong(joinPoint, operationLogAnnotation.targetId()));
            entry.setContent(jsonContent(resolveContent(joinPoint, operationLogAnnotation)));
            fillRequestMeta(entry);
            entry.setCreatedAt(LocalDateTime.now());
            operationLogMapper.insert(entry);
        } catch (Exception e) {
            /* 留痕失败不阻断业务（R47 日志为旁路审计，业务事务已在切面前提交/回滚） */
            log.warn("操作日志写入失败：method={}, type={}",
                    joinPoint.getSignature().toShortString(), operationLogAnnotation.operationType(), e);
        }
    }

    /* content 列为 JSON 类型（sys_operation_log DDL）：统一包裹 {"action":...} 结构 */
    private String jsonContent(String text) {
        String escaped = text == null ? "" : text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
        return "{\"action\":\"" + escaped + "\"}";
    }

    /* 解析 SpEL（#参数名/#result），表达式为空或求值失败返回 null */
    private Object resolveSpel(JoinPoint joinPoint, String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        StandardEvaluationContext context = new StandardEvaluationContext();
        String[] paramNames = paramNameDiscoverer.getParameterNames(method);
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < args.length; i++) {
            String name = paramNames != null && i < paramNames.length ? paramNames[i] : "arg" + i;
            context.setVariable(name, args[i]);
        }
        context.setVariable("result", null);
        return spelParser.parseExpression(expression).getValue(context);
    }

    private Long resolveLong(JoinPoint joinPoint, String expression) {
        Object value = resolveSpel(joinPoint, expression);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignore) {
                // 非数字内容不作为目标 ID
            }
        }
        return null;
    }

    /* 社区归属：SpEL 显式指定优先；否则取操作人绑定社区（ADMIN 单绑定场景）；再否则空 */
    private Long resolveCommunityId(JoinPoint joinPoint, OperationLog annotation, UserContext user) {
        Long explicit = resolveLong(joinPoint, annotation.communityId());
        if (explicit != null) {
            return explicit;
        }
        if (user != null && RoleConstants.ADMIN.equals(user.getRole())
                && user.getCommunityIds() != null && user.getCommunityIds().size() == 1) {
            return user.getCommunityIds().iterator().next();
        }
        return null;
    }

    private String resolveContent(JoinPoint joinPoint, OperationLog annotation) {
        if (annotation.content() == null || annotation.content().isBlank()) {
            return joinPoint.getSignature().toShortString();
        }
        try {
            Object value = resolveSpel(joinPoint, annotation.content());
            if (value != null) {
                return String.valueOf(value);
            }
        } catch (Exception e) {
            log.debug("操作日志内容 SpEL 求值失败，回退方法签名：{}", annotation.content());
        }
        return joinPoint.getSignature().toShortString();
    }

    /* 请求元数据：HTTP 上下文内取 IP/UA（定时任务等无请求上下文时为空） */
    private void fillRequestMeta(SysOperationLog entry) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            entry.setIp(clientIp(request));
            entry.setUserAgent(truncate(request.getHeader("User-Agent"), 255));
        }
    }

    /* 客户端真实 IP：代理链 X-Forwarded-For 优先，仅取第一段 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 50);
        }
        return truncate(request.getRemoteAddr(), 50);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
