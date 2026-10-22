package com.community.residence.common.notice.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("V46 公告目标说明迁移守卫")
class NoticeTargetVisibilityMigrationTest {

    @Test
    @DisplayName("只将公告目标列注释更新为无需居住关系可见")
    void updatesOnlyTheTargetTypeComment() throws Exception {
        String path = "/db/migration/V46__notice_target_public_visibility_comment.sql";
        String sql;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertThat(in).as("V46 迁移脚本必须存在于 classpath: " + path).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(sql).contains("ALTER TABLE notice_target")
                .contains("MODIFY COLUMN target_type VARCHAR(20) NOT NULL")
                .contains("GUEST-无需居住关系可见（target_id 固定 0）");
        assertThat(sql).containsOnlyOnce("COMMENT '")
                .doesNotContain("UPDATE ")
                .doesNotContain("DELETE ")
                .doesNotContain("CREATE TABLE")
                .doesNotContain("DROP ");
    }
}
