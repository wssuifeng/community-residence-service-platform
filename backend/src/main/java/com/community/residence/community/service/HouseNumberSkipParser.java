package com.community.residence.community.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 房号跳过项解析器（精确建房）：与前端 HouseBatchDialog.vue 的 parseSkipRules
 * 保持同一套语义，同一份输入在前后端得到同样的排除结果。
 *
 * 分隔符：中英文逗号 / 顿号 / 空白（含连续），空片段忽略。
 * 五种写法（位数即语义）：
 * <ul>
 *   <li>{@code 4}    1~2 位纯数字＝楼层，跳过该层整层；</li>
 *   <li>{@code 4:1}  楼层:序号（通配楼层写作 {@code *:4}），跳过指定层指定序号；</li>
 *   <li>{@code 04}   两位补零＝序号，跳过所有楼层的该序号；</li>
 *   <li>{@code 104}  3~4 位纯数字＝基础门牌号（楼层 + 补零序号），按文本精确匹配，
 *                    如 104 即 1 层 4 号、1004 即 10 层 4 号；</li>
 *   <li>{@code A-101} 含非数字字符＝完整门牌号（含前后缀），按完整房号文本精确匹配。</li>
 * </ul>
 * 无法识别的片段（数值 ≤0、纯数字长度 &gt;4、序号为 0 的层级写法）归入 invalid，
 * 不参与匹配也不阻断生成。
 */
public final class HouseNumberSkipParser {

    /** 楼层:序号写法（可写通配楼层 *，兼容全角冒号） */
    private static final Pattern SPOT_PATTERN = Pattern.compile("^(\\*|\\d{1,2})\\s*[:：]\\s*(\\d{1,2})$");

    /** 两位补零（01~09）即序号；一位数或无前导零的两位数是楼层 */
    private static final Pattern PADDED_SEQ_PATTERN = Pattern.compile("^0\\d$");

    /** 分隔符：中英文逗号 / 顿号 / 空白 */
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[\\s,，、]+");

    private static final Pattern DIGITS_PATTERN = Pattern.compile("^\\d+$");

    /** 跳过项最长纯数字位数（超过即无法对应任何门牌号） */
    private static final int MAX_DIGIT_LENGTH = 4;

    private HouseNumberSkipParser() {
    }

    /** 跳过项种类 */
    public enum Kind {
        /** 整层跳过 */
        FLOOR,
        /** 所有楼层的指定序号 */
        SEQ,
        /** 指定楼层指定序号 */
        SPOT,
        /** 基础门牌号（楼层 + 补零序号，不含前后缀） */
        BASE,
        /** 完整门牌号（含前后缀） */
        NUMBER
    }

    /**
     * 单条跳过规则：floor/seq 供 FLOOR/SEQ/SPOT 使用，text 供 BASE/NUMBER 文本比对（大写）。
     */
    public record Rule(String raw, Kind kind, Integer floor, Integer seq, String text) {

        /** 该规则是否命中一行生成项 */
        public boolean matches(int floor, int seq, String base, String houseNumber) {
            return switch (kind) {
                case FLOOR -> floor == this.floor;
                case SEQ -> seq == this.seq;
                case SPOT -> floor == this.floor && seq == this.seq;
                case BASE -> text.equalsIgnoreCase(base);
                case NUMBER -> text.equalsIgnoreCase(houseNumber);
            };
        }

        /** 语义回显（预览与提示文案） */
        public String label() {
            return switch (kind) {
                case FLOOR -> floor + " 层整层";
                case SEQ -> "每层 " + String.format("%02d", seq) + " 号";
                case SPOT -> floor + " 层 " + seq + " 号";
                case BASE, NUMBER -> "房号 " + text;
            };
        }
    }

    /** 解析结果：有效规则 + 无法识别的片段 */
    public record Result(List<Rule> rules, List<String> invalid) {

        /** 是否有任一规则命中该行 */
        public boolean matchesAny(int floor, int seq, String base, String houseNumber) {
            for (Rule rule : rules) {
                if (rule.matches(floor, seq, base, houseNumber)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 解析跳过项表达式（空/空白输入返回空规则集） */
    public static Result parse(String raw) {
        List<Rule> rules = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return new Result(rules, invalid);
        }
        for (String token : SEPARATOR_PATTERN.split(raw.trim())) {
            if (token.isEmpty()) {
                continue;
            }
            Matcher spot = SPOT_PATTERN.matcher(token);
            if (spot.matches()) {
                int seq = Integer.parseInt(spot.group(2));
                if (seq <= 0) {
                    invalid.add(token);
                } else if ("*".equals(spot.group(1))) {
                    rules.add(new Rule(token, Kind.SEQ, null, seq, null));
                } else {
                    rules.add(new Rule(token, Kind.SPOT, Integer.parseInt(spot.group(1)), seq, null));
                }
                continue;
            }
            if (DIGITS_PATTERN.matcher(token).matches()) {
                if (token.length() > MAX_DIGIT_LENGTH || Long.parseLong(token) <= 0) {
                    invalid.add(token);
                } else if (PADDED_SEQ_PATTERN.matcher(token).matches()) {
                    rules.add(new Rule(token, Kind.SEQ, null, Integer.parseInt(token), null));
                } else if (token.length() <= 2) {
                    rules.add(new Rule(token, Kind.FLOOR, Integer.parseInt(token), null, null));
                } else {
                    rules.add(new Rule(token, Kind.BASE, null, null, token.toUpperCase()));
                }
                continue;
            }
            rules.add(new Rule(token, Kind.NUMBER, null, null, token.toUpperCase()));
        }
        return new Result(rules, invalid);
    }
}
