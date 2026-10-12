package com.community.residence.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知调用点身份空间标注守卫（DEF-067）。
 * <p>
 * 全量枚举 {@code backend/src/main/java} 下所有通知生成调用点，断言每个调用点都显式声明了
 * 收件人身份空间（{@code createForResident}/{@code createForUser}），并逐一核对
 * 「文件 + 收件人表达式 + 标注值」三元组——方法名即 owner_type 标注，取代隐式默认值。
 * 新增调用点若未登记在本用例清单内、或误用旧的无身份入口，本用例即失败。
 */
@DisplayName("通知调用点身份空间标注守卫（DEF-067）")
class NotificationOwnerTypeCallSiteGuardTest {

    /** 旧入口（无身份标注）签名片段：已私有化，源码层不得再出现 */
    private static final String LEGACY_ENTRY = "notificationService.create(";

    /** 任何通知生成调用点的公共前缀（用于统计文件内调用点总数，防漏登记的调用点） */
    private static final String CALL_PREFIX = "notificationService.createFor";

    /** 期望清单：文件 → 「调用点片段 × 次数」（收件人表达式 + 身份标注方法名） */
    private static final Map<String, Map<String, Integer>> EXPECTED_CALL_SITES = expectedCallSites();

    @Test
    @DisplayName("禁止无身份标注的通知入口：源码中不得出现 notificationService.create(")
    void legacyEntry_notUsed() {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaSources()) {
            if (read(file).contains(LEGACY_ENTRY)) {
                offenders.add(relative(file));
            }
        }
        assertThat(offenders)
                .as("通知收件人身份空间必须显式声明（createForUser/createForResident），不得走隐式默认入口")
                .isEmpty();
    }

    @Test
    @DisplayName("调用点全量覆盖：无一漏标、无一漏登记")
    void everyCallSite_declaresOwnerType() {
        Set<String> filesWithCalls = new TreeSet<>();
        for (Path file : javaSources()) {
            String source = read(file);
            int actualCalls = count(source, CALL_PREFIX);
            if (actualCalls == 0) {
                continue;
            }
            String relative = relative(file);
            filesWithCalls.add(relative);

            Map<String, Integer> expected = EXPECTED_CALL_SITES.get(relative);
            assertThat(expected)
                    .as("文件 %s 存在 %d 个通知调用点但未登记身份标注清单", relative, actualCalls)
                    .isNotNull();
            assertThat(expected.values().stream().mapToInt(Integer::intValue).sum())
                    .as("文件 %s 的调用点数量与登记清单不一致（疑似新增/删除调用点）", relative)
                    .isEqualTo(actualCalls);
            expected.forEach((snippet, times) -> assertThat(count(source, snippet))
                    .as("文件 %s 中「%s」出现次数（身份标注值）", relative, snippet)
                    .isEqualTo(times));
        }
        assertThat(filesWithCalls)
                .as("通知调用点所在文件集合")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_CALL_SITES.keySet());
    }

    private static Map<String, Map<String, Integer>> expectedCallSites() {
        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        /* 居民主体：lease.tenantId / application.residentId / payment.residentId /
           appointment.userId / reservation.userId / 公告群发 residentId */
        expected.put("com/community/residence/agreement/service/AgreementService.java",
                calls("notificationService.createForResident(lease.getTenantId()", 3,
                        "notificationService.createForUser(admin.getId()", 1));
        expected.put("com/community/residence/feedback/service/FeedbackService.java",
                calls("notificationService.createForResident(feedback.getResidentId()", 2,
                        "notificationService.createForUser(feedback.getHandlerId()", 1));
        expected.put("com/community/residence/housing/service/ViewingAppointmentService.java",
                calls("notificationService.createForResident(appointment.getUserId()", 1,
                        "notificationService.createForUser(assigneeId", 1,
                        "notificationService.createForUser(adminId", 1));
        /* R72 押金退还结果通知租约居民（resident 身份空间） */
        expected.put("com/community/residence/lease/service/DepositRefundService.java",
                calls("notificationService.createForResident(lease.getTenantId()", 1));
        expected.put("com/community/residence/lease/service/LeaseMoveOutApplicationService.java",
                calls("notificationService.createForUser(adminId", 1,
                        "notificationService.createForResident(application.getResidentId()", 2));
        expected.put("com/community/residence/notice/service/NoticeService.java",
                calls("notificationService.createForResident(residentId", 1));
        expected.put("com/community/residence/payment/service/PaymentService.java",
                calls("notificationService.createForResident(current.getResidentId()", 2,
                        "notificationService.createForUser(adminId", 2));
        expected.put("com/community/residence/resident/service/ResidenceApplicationService.java",
                calls("notificationService.createForResident(application.getResidentId()", 3));
        expected.put("com/community/residence/reservation/service/ReservationService.java",
                calls("notificationService.createForResident(reservation.getUserId()", 3));
        /* 员工/管理员主体：boundAdminIds 绑定管理员 / assigneeId 派单人 / 超管账号 */
        expected.put("com/community/residence/schedule/task/LeaseExpiryReminderTask.java",
                calls("notificationService.createForResident(lease.getTenantId()", 1,
                        "notificationService.createForUser(adminId", 1));
        expected.put("com/community/residence/schedule/task/LogRetentionCleanTask.java",
                calls("notificationService.createForUser(admin.getId()", 1));
        /* R74 工单链路：提交→社区绑定管理员+超管（adminId）、受理/派单/接单/开始处理/提交结果/
           终审关闭→居民提交人（order.getResidentId()）+ 相关管理员（adminId） */
        expected.put("com/community/residence/workorder/service/WorkOrderService.java",
                calls("notificationService.createForUser(dto.getAssigneeId()", 1,
                        "notificationService.createForUser(adminId", 2,
                        "notificationService.createForResident(order.getResidentId()", 7,
                        "notificationService.createForUser(latest.getAssigneeId()", 1));
        return expected;
    }

    private static Map<String, Integer> calls(String snippet, int times) {
        Map<String, Integer> single = new LinkedHashMap<>();
        single.put(snippet, times);
        return single;
    }

    private static Map<String, Integer> calls(String first, int firstTimes,
                                              String second, int secondTimes) {
        Map<String, Integer> both = new LinkedHashMap<>(calls(first, firstTimes));
        both.put(second, secondTimes);
        return both;
    }

    private static Map<String, Integer> calls(String first, int firstTimes,
                                              String second, int secondTimes,
                                              String third, int thirdTimes) {
        Map<String, Integer> all = new LinkedHashMap<>(calls(first, firstTimes, second, secondTimes));
        all.put(third, thirdTimes);
        return all;
    }

    private static Map<String, Integer> calls(String first, int firstTimes,
                                              String second, int secondTimes,
                                              String third, int thirdTimes,
                                              String fourth, int fourthTimes) {
        Map<String, Integer> all = new LinkedHashMap<>(
                calls(first, firstTimes, second, secondTimes, third, thirdTimes));
        all.put(fourth, fourthTimes);
        return all;
    }

    /** 主源码目录（surefire 工作目录为模块根 backend/；兼容从仓库根运行） */
    private static Path mainSourceRoot() {
        Path direct = Path.of("src", "main", "java");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        Path fromRoot = Path.of("backend", "src", "main", "java");
        assertThat(Files.isDirectory(fromRoot))
                .as("未找到后端主源码目录（src/main/java）")
                .isTrue();
        return fromRoot;
    }

    private static List<Path> javaSources() {
        Path root = mainSourceRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path file) {
        return mainSourceRoot().relativize(file).toString().replace('\\', '/');
    }

    private static int count(String source, String snippet) {
        int count = 0;
        int index = source.indexOf(snippet);
        while (index >= 0) {
            count++;
            index = source.indexOf(snippet, index + snippet.length());
        }
        return count;
    }
}
