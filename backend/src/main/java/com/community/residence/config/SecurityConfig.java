package com.community.residence.config;

import com.community.residence.common.constant.ErrorCode;
import com.community.residence.common.result.ApiResponse;
import com.community.residence.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * 安全配置：无状态 JWT 认证 + 五角色功能级权限（@PreAuthorize）。
 * 数据级权限由 DataScopeInterceptor 按 UserContext 注入（架构设计.md §3.2 两层模型）。
 * 401/403 返回统一 ApiResponse 格式（含错误场景不走 GlobalExceptionHandler 的过滤器层）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 过滤器层 401/403 响应序列化用独立实例，不注入业务 Bean，避免与 SecurityConfig 形成循环依赖 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 认证接口与接口文档公开
                        .requestMatchers("/api/v1/auth/**", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // 未认证：401 + 统一响应体
                        .authenticationEntryPoint((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        ErrorCode.UNAUTHORIZED, "未登录或令牌已失效"))
                        // 无权限：403 + 统一响应体（越权尝试记日志由日志切面与 AOP 覆盖）
                        .accessDeniedHandler((request, response, e) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        ErrorCode.FORBIDDEN, "无权限访问")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        /* BCrypt 默认强度 10：平衡安全与登录性能（架构设计.md §8b） */
        return new BCryptPasswordEncoder();
    }

    private void writeError(HttpServletResponse response, int status,
                            ErrorCode code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }
}
