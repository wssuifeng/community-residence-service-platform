package com.community.residence.housing.mapper;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「看房 → 租约 指向修正」SQL 守卫（DEF-081）：
 * `ViewingAppointmentMapper.linkToLease` 必须①**只补空指向**（`lease_id IS NULL`，幂等、
 * 不覆盖既有非空指向）；②只改指向字段，**不得动看房状态**（看房是沟通手段，不是承约门槛）；
 * ③按（居民 + 本房屋的挂牌房源）匹配，不得全表乱写；④游客预约（user_id 为空）不命中。
 */
@DisplayName("看房→租约指向修正 SQL 守卫（DEF-081）")
class ViewingLeaseLinkGuardTest {

    private String sql() throws Exception {
        Method method = ViewingAppointmentMapper.class.getMethod("linkToLease", Long.class, Long.class, Long.class);
        Update update = method.getAnnotation(Update.class);
        assertThat(update).as("linkToLease 必须带 @Update 注解").isNotNull();
        return String.join(" ", update.value());
    }

    @Test
    @DisplayName("只补空指向：lease_id IS NULL 守卫（幂等、不覆盖既有指向）")
    void onlyFillsNullLink() throws Exception {
        String sql = sql();

        assertThat(sql).contains("UPDATE viewing_appointment");
        assertThat(sql).contains("SET va.lease_id = #{leaseId}");
        assertThat(sql).contains("va.lease_id IS NULL");
    }

    @Test
    @DisplayName("不碰看房状态：只改 lease_id 与 updated_at（不得把看房变成门槛）")
    void doesNotTouchViewingStatus() throws Exception {
        String sql = sql();

        assertThat(sql).contains("va.updated_at = NOW()");
        assertThat(sql).doesNotContain("va.status");
        assertThat(sql).doesNotContain("COMPLETED");
        assertThat(sql).doesNotContain("RESERVED");
    }

    @Test
    @DisplayName("匹配口径：按居民 + 本房屋的挂牌房源（游客预约 user_id 为空自然不命中）")
    void matchesResidentAndHouseListings() throws Exception {
        String sql = sql();

        assertThat(sql).contains("va.user_id = #{residentId}");
        assertThat(sql).contains("va.housing_id IN (SELECT h.id FROM housing h WHERE h.house_id = #{houseId})");
    }
}
