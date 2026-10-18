package com.community.residence.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 离线注册真实Controller映射；包迁移不得丢失契约、角色及参数约束。 */
class ControllerMappingCompatibilityTest {
    private static final Path BASELINE = Path.of("src/test/resources/architecture/http-contract.tsv");

    static class MappingRegistry extends RequestMappingHandlerMapping {
        RequestMappingInfo mapping(Method method, Class<?> type) { return getMappingForMethod(method, type); }
    }

    @Test
    void registeredContractsAndAuthorizationRemainCompatible() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var registry = new MappingRegistry();
        var rows = new TreeSet<String>();
        for (var candidate : scanner.findCandidateComponents("com.community.residence")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            Object bean = mock(type);
            for (Method method : type.getDeclaredMethods()) {
                RequestMappingInfo info = registry.mapping(method, type);
                if (info == null) continue;
                registry.registerMapping(info, bean, method);
                PreAuthorize role = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
                if (role == null) role = AnnotatedElementUtils.findMergedAnnotation(type, PreAuthorize.class);
                String parameters = Arrays.stream(method.getParameters()).map(parameter ->
                        canonical(parameter.getParameterizedType().getTypeName()) + ":" +
                                Arrays.toString(parameter.getAnnotations())).reduce((a,b) -> a + ";" + b).orElse("");
                rows.add(info + "\t" + (role == null ? "" : role.value()) + "\t" + parameters + "\t" +
                        canonical(method.getGenericReturnType().getTypeName()));
            }
        }
        assertThat(rows).hasSizeGreaterThan(250);
        if (Boolean.getBoolean("architecture.recordContracts")) {
            Files.createDirectories(BASELINE.getParent());
            Files.write(BASELINE, rows);
        }
        List<String> expected = Files.readAllLines(BASELINE);
        assertThat(new ArrayList<>(rows)).containsExactlyElementsOf(expected);
        assertThat(registry.getHandlerMethods()).hasSize(rows.size());
    }

    private static String canonical(String type) {
        return type.replaceAll("com\\.community\\.residence(?:\\.[a-z][a-z0-9_]*)*\\.", "");
    }
}
