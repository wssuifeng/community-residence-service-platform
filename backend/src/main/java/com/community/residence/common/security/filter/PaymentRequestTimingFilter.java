package com.community.residence.common.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import java.io.IOException;
import java.util.Locale;

/** 只观察发起付款的应用耗时，不记录支付表单、签名、令牌或密钥，不调用支付渠道。 */
@Slf4j @Component
public class PaymentRequestTimingFilter extends OncePerRequestFilter {
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().matches("/api/v1/leases/[0-9]+/(first|rent|renew)-payments");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        long started=System.nanoTime();var wrapped=new ContentCachingResponseWrapper(response);
        try { chain.doFilter(request,wrapped); }
        finally {
            double millis=(System.nanoTime()-started)/1_000_000.0;
            wrapped.setHeader("Server-Timing",String.format(Locale.ROOT,"payment_app;dur=%.1f",millis));
            log.info("支付发起应用耗时：purpose={}, appMillis={}, httpStatus={}",request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/')+1),Math.round(millis),wrapped.getStatus());
            wrapped.copyBodyToResponse();
        }
    }
}
