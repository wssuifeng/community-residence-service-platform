package com.community.residence.community.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V29 迁移脚本守卫（公共资源展示图片，R75）：
 * ①唯一结构变更为 `public_resource` 增列 `images VARCHAR(1000) NULL`（位于 description 之后，
 *   与 housing.images 同口径）；②只加一列——不建表、不改数据、不触碰 V1~V28 其它表结构；
 * ③迁移版本序列按当前 V30 连续；V29 本身不建表，V30 独立配置表另有守卫。
 */
@DisplayName("V29 公共资源图片脚本守卫")
class PublicResourceImagesMigrationTest {

    private static final String SCRIPT = "/db/migration/V29__public_resource_images.sql";

    private String readScript() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SCRIPT)) {
            assertThat(in).as("V29 迁移脚本必须存在于 classpath: " + SCRIPT).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("结构变更：public_resource 增列 images VARCHAR(1000) NULL（description 之后）")
    void addsImagesColumnToPublicResource() throws Exception {
        String sql = readScript();

        assertThat(sql).contains("ALTER TABLE public_resource");
        assertThat(sql).contains("ADD COLUMN images VARCHAR(1000) NULL");
        assertThat(sql).contains("COMMENT '展示图片地址（逗号分隔，与 housing.images 同口径）'");
        assertThat(sql).contains("AFTER description");
    }

    @Test
    @DisplayName("只加一列：不建表、不改数据、不触碰 V1~V28 其它表结构（表数不变）")
    void onlyAddsColumnWithoutTouchingExistingSchema() throws Exception {
        String sql = readScript();

        assertThat(sql).containsOnlyOnce("ALTER TABLE");
        assertThat(sql).doesNotContain("CREATE TABLE");
        assertThat(sql).doesNotContain("DROP ");
        assertThat(sql).doesNotContain("UPDATE ");
        assertThat(sql).doesNotContain("DELETE ");
        assertThat(sql).doesNotContain("ALTER TABLE housing");
        assertThat(sql).doesNotContain("ALTER TABLE community");
        assertThat(sql).doesNotContain("ALTER TABLE resource_reservation");
        assertThat(sql).doesNotContain("ALTER TABLE resource_timeslot");
    }

    @Test
    @DisplayName("版本序列连续：V1~V42 均保持原位")
    void migrationChainStaysContiguous() throws Exception {
        File dir = new File(getClass().getResource("/db/migration").toURI());
        String[] names = dir.list();
        assertThat(names).as("/db/migration 资源目录应可列出").isNotNull();

        List<String> sqlFiles = Arrays.stream(names)
                .filter(name -> name.endsWith(".sql"))
                .toList();
        assertThat(sqlFiles).hasSize(42);
        assertThat(sqlFiles).contains("V29__public_resource_images.sql");
        for (int version = 1; version <= 42; version++) {
            final int v = version;
            assertThat(sqlFiles)
                    .as("V" + v + " 脚本必须仍在位（已应用迁移不得删除 / 改名）")
                    .anySatisfy(name -> assertThat(name).startsWith("V" + v + "__"));
        }
    }
}
