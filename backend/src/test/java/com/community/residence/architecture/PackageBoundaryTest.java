package com.community.residence.architecture;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** 结构边界、Bean名称与注解SQL装配，全部离线运行。 */
class PackageBoundaryTest {
    private static final Path ROOT = Path.of("src/main/java/com/community/residence");

    @Test void sourceRootContainsOnlyPortalGroups() throws Exception {
        try (var directories = Files.list(ROOT)) {
            assertThat(directories.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("admin", "resident", "staff", "common");
        }
    }
    private static final Pattern REFERENCE = Pattern.compile("com\\.community\\.residence\\.(admin|resident|staff)\\.");

    @Test void portalDependenciesAndControllerLayersRemainDirected() throws Exception {
        var violations = new ArrayList<String>();
        try (var files = Files.walk(ROOT)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                var relative = ROOT.relativize(path);
                if (relative.getNameCount() == 1) {
                    assertThat(path.getFileName().toString()).isEqualTo("CommunityResidenceApplication.java");
                    continue;
                }
                String owner = relative.getName(0).toString();
                assertThat(owner).isIn("admin", "resident", "staff", "common");
                assertThat(relative.getName(1).toString()).as("业务或技术模块应有明确用途").isNotEqualTo("common");
                // Package declarations express ownership, not an imported dependency.
                source = source.replaceAll("(?m)^package[^;]+;", "");
                var matcher = REFERENCE.matcher(source);
                while (matcher.find()) if (!owner.equals(matcher.group(1))) violations.add(relative + " -> " + matcher.group(1));
                if (source.contains("@RestController")) {
                    assertThat(source).as(relative.toString()).doesNotMatch("(?s).*private\\s+(?:final\\s+)?[\\w.]*Mapper\\s+\\w+.*");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test void componentBeanNamesAreUnique() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));
        var names = new HashMap<String, String>();
        for (var bean : scanner.findCandidateComponents("com.community.residence")) {
            String type = bean.getBeanClassName();
            String name = new org.springframework.context.annotation.AnnotationBeanNameGenerator().generateBeanName(bean, new org.springframework.beans.factory.support.DefaultListableBeanFactory());
            assertThat(names.putIfAbsent(name, type)).as("Duplicate bean: " + name).isNull();
        }
        assertThat(names).hasSizeGreaterThan(150);
    }

    @Test void allMappersAndAnnotationSqlCanBeRegistered() throws Exception {
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        int count = 0;
        try (var files = Files.walk(ROOT)) {
            for (var file : files.filter(p -> p.toString().endsWith("Mapper.java")).toList()) {
                String fqn = "com.community.residence." + ROOT.relativize(file).toString().replace('\\', '.').replace('/', '.').replace(".java", "");
                Class<?> type = Class.forName(fqn);
                if (type.isAnnotationPresent(Mapper.class)) { config.addMapper(type); count++; }
            }
        }
        assertThat(count).isEqualTo(60);
        assertThat(config.getMappedStatementNames()).hasSizeGreaterThan(100);
    }
}
