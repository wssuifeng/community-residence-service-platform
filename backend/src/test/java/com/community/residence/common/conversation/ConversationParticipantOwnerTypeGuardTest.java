package com.community.residence.common.conversation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话参与者身份空间标注守卫（DEF-075，模式同 {@code NotificationOwnerTypeCallSiteGuardTest}）。
 *
 * 两条硬纪律：
 * ① 入群调用 `addParticipant(...)` **必须显式声明身份空间**（三参重载）；唯一登记的
 *    遗留两参调用是看房带看人分配（`housing/ViewingAppointmentService`，入群人是 STAFF
 *    账号 ⇒ USER 语义，由服务端兼容入口收口）。新增两参调用会让本用例失败。
 * ② 参与者查询谓词 `.eq(ConversationParticipant::getUserId, …)` **必须与
 *    `.eq(ConversationParticipant::getOwnerType, …)` 成对出现**（每文件计数相等），
 *    且只允许出现在已登记的两个文件内——防止再出现"只按 user_id 判定"的回归。
 */
@DisplayName("会话参与者身份空间标注守卫（DEF-075）")
class ConversationParticipantOwnerTypeGuardTest {

    /** 唯一登记的遗留两参入群调用点（带看人 = sys_user 主体） */
    private static final String LEGACY_CALL_SITE =
            "com/community/residence/common/housing/service/ViewingAppointmentService.java";

    /** 允许出现参与者查询谓词的文件 */
    private static final List<String> PREDICATE_FILES = List.of(
            "com/community/residence/common/conversation/service/ConversationService.java",
            "com/community/residence/common/config/ConversationSubscriptionInterceptor.java");

    private static final Pattern CALL = Pattern.compile("addParticipant\\(");
    private static final String USER_ID_PREDICATE = ".eq(ConversationParticipant::getUserId";
    private static final String OWNER_TYPE_PREDICATE = ".eq(ConversationParticipant::getOwnerType";

    @Test
    @DisplayName("入群调用必须显式带身份空间：三参重载；遗留两参仅限已登记的带看人调用点")
    void addParticipantCallSites_declareOwnerType() {
        List<String> legacyOffenders = new ArrayList<>();
        List<String> twoArgCalls = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = read(file);
            if (!source.contains("addParticipant(")) {
                continue;
            }
            String relative = relative(file);
            Matcher matcher = CALL.matcher(source);
            while (matcher.find()) {
                int open = matcher.end() - 1;
                String args = argumentList(source, open);
                if (args == null) {
                    continue;
                }
                int lineStart = source.lastIndexOf('\n', matcher.start()) + 1;
                String signaturePrefix = source.substring(lineStart, matcher.start());
                /* 跳过方法声明本身（形参列表） */
                if (signaturePrefix.contains("void ")) {
                    continue;
                }
                int argCount = countTopLevelArgs(args);
                if (argCount == 2) {
                    twoArgCalls.add(relative);
                    if (!LEGACY_CALL_SITE.equals(relative)) {
                        legacyOffenders.add(relative + " → addParticipant(" + args.trim() + ")");
                    }
                } else if (argCount != 3) {
                    legacyOffenders.add(relative + " → 参数个数非法（" + argCount + "）：addParticipant("
                            + args.trim() + ")");
                }
            }
        }
        assertThat(legacyOffenders)
                .as("入群必须显式声明身份空间（owner_type），两参重载仅限登记的带看人调用点")
                .isEmpty();
        assertThat(twoArgCalls)
                .as("遗留两参 addParticipant 调用点清单")
                .containsExactlyInAnyOrder(LEGACY_CALL_SITE);
    }

    /** 取 `(` 起配对的实参文本（支持嵌套括号），未闭合返回 null */
    private static String argumentList(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return source.substring(openIndex + 1, i);
                }
            }
        }
        return null;
    }

    /** 顶层逗号个数 + 1（忽略嵌套括号内逗号） */
    private static int countTopLevelArgs(String args) {
        if (args.isBlank()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (char c : args.toCharArray()) {
            if (c == '(' || c == '<') {
                depth++;
            } else if (c == ')' || c == '>') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("参与者查询谓词必须与身份空间成对：getUserId 与 getOwnerType 每文件计数相等")
    void participantQueries_pairOwnerType() {
        Set<String> files = new TreeSet<>();
        for (Path file : javaSources()) {
            String source = read(file);
            int userIdPredicates = count(source, USER_ID_PREDICATE);
            int ownerTypePredicates = count(source, OWNER_TYPE_PREDICATE);
            if (userIdPredicates == 0 && ownerTypePredicates == 0) {
                continue;
            }
            String relative = relative(file);
            files.add(relative);
            assertThat(ownerTypePredicates)
                    .as("文件 %s：参与者 user_id 谓词 %d 个，必须与 owner_type 谓词成对（当前 %d 个）",
                            relative, userIdPredicates, ownerTypePredicates)
                    .isEqualTo(userIdPredicates);
            assertThat(count(source, ".in(ConversationParticipant::getUserId"))
                    .as("文件 %s：不得按 user_id 集合查询参与者（身份空间不确定）", relative)
                    .isZero();
        }
        assertThat(files)
                .as("允许出现参与者查询谓词的文件清单（新增文件须在此登记）")
                .containsExactlyInAnyOrderElementsOf(PREDICATE_FILES);
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
