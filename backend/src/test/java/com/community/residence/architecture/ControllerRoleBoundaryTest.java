package com.community.residence.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 使用真实方法安全代理验证拆分后的角色拒绝，依赖均为替身。 */
class ControllerRoleBoundaryTest {
    @Configuration @EnableMethodSecurity static class SecurityOnly {}

    @Test void outsiderCannotCallRoleRestrictedHandlers() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SecurityOnly.class);
            var scanner = new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
            for (var bean : scanner.findCandidateComponents("com.community.residence")) {
                Class<?> type = Class.forName(bean.getBeanClassName());
                context.registerBean(type.getName(), (Class) type, () -> {
                    try {
                        var constructor = type.getConstructors()[0];
                        Object[] args = java.util.Arrays.stream(constructor.getParameterTypes()).map(t -> mock(t)).toArray();
                        return constructor.newInstance(args);
                    } catch (Exception e) { throw new IllegalStateException(e); }
                });
            }
            context.refresh();
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("outsider", "", List.of(new SimpleGrantedAuthority("ROLE_OUTSIDER"))));
            int checked = 0;
            for (String name : context.getBeanDefinitionNames()) {
                if (!name.startsWith("com.community.residence.")) continue;
                Class<?> type = Class.forName(name);
                for (var method : type.getDeclaredMethods()) {
                    var auth = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                    if (auth == null) auth = org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
                    if (auth == null || !auth.value().contains("has") || !java.lang.reflect.Modifier.isPublic(method.getModifiers())) continue;
                    Object bean = context.getBean(name);
                    assertThatThrownBy(() -> {
                        try { method.invoke(bean, java.util.Arrays.stream(method.getParameterTypes()).map(t ->
                            !t.isPrimitive() ? null : t == boolean.class ? false : t == long.class ? (Object) 0L : t == double.class ? (Object) 0D : t == float.class ? (Object) 0F : t == char.class ? (Object) '\0' : (Object) 0).toArray()); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    }).as(name + "#" + method.getName()).isInstanceOf(AccessDeniedException.class);
                    checked++;
                }
            }
            assertThat(checked).isGreaterThan(200);
        } finally { SecurityContextHolder.clearContext(); }
    }
}
