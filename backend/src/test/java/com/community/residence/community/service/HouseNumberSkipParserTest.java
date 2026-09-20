package com.community.residence.community.service;

import com.community.residence.community.service.HouseNumberSkipParser.Kind;
import com.community.residence.community.service.HouseNumberSkipParser.Result;
import com.community.residence.community.service.HouseNumberSkipParser.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 房号跳过项解析测试（精确建房）：与前端 HouseBatchDialog.parseSkipRules 同一口径，
 * 五种写法逐条核对（4 整层 / 4:1 指定层房号 / 04 跨层序号 / 104 基础门牌号 / A-101 完整房号）。
 */
@DisplayName("HouseNumberSkipParser 跳过语法单元测试")
class HouseNumberSkipParserTest {

    /* ---- 写法 1：1~2 位纯数字 = 整层 ---- */

    @Test
    @DisplayName("写法 1「4」＝第 4 层整层（楼层规则，命中该层全部房号）")
    void plainFloorNumber() {
        Result result = HouseNumberSkipParser.parse("4");

        assertThat(result.invalid()).isEmpty();
        assertThat(result.rules()).hasSize(1);
        Rule rule = result.rules().get(0);
        assertThat(rule.kind()).isEqualTo(Kind.FLOOR);
        assertThat(rule.floor()).isEqualTo(4);
        assertThat(rule.seq()).isNull();
        assertThat(result.matchesAny(4, 1, "401", "401")).isTrue();
        assertThat(result.matchesAny(4, 6, "406", "406")).isTrue();
        assertThat(result.matchesAny(3, 4, "304", "304")).isFalse();
        assertThat(rule.label()).isEqualTo("4 层整层");
    }

    @Test
    @DisplayName("写法 1 边界：12 为两位数楼层（不是序号），两位数无前导零即楼层")
    void twoDigitFloorNumber() {
        Result result = HouseNumberSkipParser.parse("12");

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).kind()).isEqualTo(Kind.FLOOR);
        assertThat(result.rules().get(0).floor()).isEqualTo(12);
    }

    /* ---- 写法 2：楼层:序号 ---- */

    @Test
    @DisplayName("写法 2「4:1」＝第 4 层 1 号（精确到单套）")
    void spotRule() {
        Result result = HouseNumberSkipParser.parse("4:1");

        assertThat(result.invalid()).isEmpty();
        Rule rule = result.rules().get(0);
        assertThat(rule.kind()).isEqualTo(Kind.SPOT);
        assertThat(rule.floor()).isEqualTo(4);
        assertThat(rule.seq()).isEqualTo(1);
        assertThat(result.matchesAny(4, 1, "401", "401")).isTrue();
        assertThat(result.matchesAny(4, 2, "402", "402")).isFalse();
        assertThat(result.matchesAny(1, 4, "104", "104")).isFalse();
        assertThat(rule.label()).isEqualTo("4 层 1 号");
    }

    @Test
    @DisplayName("写法 2 变体：全角冒号与通配楼层「*:4」＝所有楼层的 4 号（等价于 04）")
    void spotRuleVariants() {
        Result fullWidth = HouseNumberSkipParser.parse("4：1");
        assertThat(fullWidth.rules()).hasSize(1);
        assertThat(fullWidth.rules().get(0).kind()).isEqualTo(Kind.SPOT);

        Result wildcard = HouseNumberSkipParser.parse("*:4");
        assertThat(wildcard.rules()).hasSize(1);
        assertThat(wildcard.rules().get(0).kind()).isEqualTo(Kind.SEQ);
        assertThat(wildcard.rules().get(0).seq()).isEqualTo(4);
        assertThat(wildcard.matchesAny(1, 4, "104", "104")).isTrue();
        assertThat(wildcard.matchesAny(9, 4, "904", "904")).isTrue();
        assertThat(wildcard.matchesAny(9, 5, "905", "905")).isFalse();

        Result seqZero = HouseNumberSkipParser.parse("4:0");
        assertThat(seqZero.rules()).isEmpty();
        assertThat(seqZero.invalid()).containsExactly("4:0");
    }

    /* ---- 写法 3：两位补零 = 跨层序号 ---- */

    @Test
    @DisplayName("写法 3「04」＝所有楼层的 4 号（补零宽度即语义）")
    void paddedSeqRule() {
        Result result = HouseNumberSkipParser.parse("04");

        assertThat(result.invalid()).isEmpty();
        Rule rule = result.rules().get(0);
        assertThat(rule.kind()).isEqualTo(Kind.SEQ);
        assertThat(rule.seq()).isEqualTo(4);
        assertThat(rule.floor()).isNull();
        assertThat(result.matchesAny(1, 4, "104", "104")).isTrue();
        assertThat(result.matchesAny(18, 4, "1804", "1804")).isTrue();
        assertThat(result.matchesAny(18, 3, "1803", "1803")).isFalse();
        assertThat(rule.label()).isEqualTo("每层 04 号");
    }

    /* ---- 写法 4：3~4 位纯数字 = 基础门牌号（楼层 + 补零序号） ---- */

    @Test
    @DisplayName("写法 4「104」＝基础门牌号精确匹配（1 层 4 号，不是 10 层 4 号）")
    void baseHouseNumber() {
        Result result = HouseNumberSkipParser.parse("104");

        assertThat(result.invalid()).isEmpty();
        Rule rule = result.rules().get(0);
        assertThat(rule.kind()).isEqualTo(Kind.BASE);
        assertThat(rule.text()).isEqualTo("104");
        assertThat(result.matchesAny(1, 4, "104", "104")).isTrue();
        assertThat(result.matchesAny(10, 4, "1004", "1004")).isFalse();
        assertThat(rule.label()).isEqualTo("房号 104");
    }

    @Test
    @DisplayName("写法 4 四位：1004＝10 层 4 号（基础门牌号按文本精确匹配）")
    void baseHouseNumberFourDigits() {
        Result result = HouseNumberSkipParser.parse("1004");

        assertThat(result.rules().get(0).kind()).isEqualTo(Kind.BASE);
        assertThat(result.rules().get(0).text()).isEqualTo("1004");
        assertThat(result.matchesAny(10, 4, "1004", "1004")).isTrue();
        assertThat(result.matchesAny(1, 4, "104", "104")).isFalse();
    }

    /* ---- 写法 5：含非数字 = 完整门牌号（房源前后缀场景） ---- */

    @Test
    @DisplayName("写法 5「A-101」＝含前缀的完整门牌号，按完整房号文本匹配（不误伤无前缀的 101）")
    void fullHouseNumber() {
        Result result = HouseNumberSkipParser.parse("A-101");

        assertThat(result.invalid()).isEmpty();
        Rule rule = result.rules().get(0);
        assertThat(rule.kind()).isEqualTo(Kind.NUMBER);
        assertThat(rule.text()).isEqualTo("A-101");
        assertThat(result.matchesAny(1, 1, "101", "A-101")).isTrue();
        assertThat(result.matchesAny(1, 1, "101", "101")).isFalse();
        assertThat(rule.label()).isEqualTo("房号 A-101");
    }

    @Test
    @DisplayName("写法 5 大小写不敏感：a-101 与生成的 A-101 命中同一套（与前端 toUpperCase 比对一致）")
    void fullHouseNumberCaseInsensitive() {
        Result result = HouseNumberSkipParser.parse("a-101");

        assertThat(result.rules().get(0).text()).isEqualTo("A-101");
        assertThat(result.matchesAny(1, 1, "101", "A-101")).isTrue();
        assertThat(result.matchesAny(1, 1, "101", "B-101")).isFalse();
    }

    /* ---- 分隔符、混合与非法片段 ---- */

    @Test
    @DisplayName("分隔符：中英文逗号/顿号/空格混写并按出现顺序解析（5 条规则各就各位）")
    void mixedSeparators() {
        Result result = HouseNumberSkipParser.parse("4，4:1、 04  104\tA-101");

        assertThat(result.invalid()).isEmpty();
        assertThat(result.rules()).extracting(Rule::kind).containsExactly(
                Kind.FLOOR, Kind.SPOT, Kind.SEQ, Kind.BASE, Kind.NUMBER);
        assertThat(result.rules()).extracting(Rule::raw)
                .containsExactly("4", "4:1", "04", "104", "A-101");
    }

    @Test
    @DisplayName("无法识别的片段（0 / 五位数字 / 序号 0）归入 invalid，不参与匹配也不阻断生成")
    void invalidTokens() {
        Result result = HouseNumberSkipParser.parse("0 12345 4:0 04");

        assertThat(result.rules()).hasSize(1);
        assertThat(result.rules().get(0).kind()).isEqualTo(Kind.SEQ);
        assertThat(result.invalid()).containsExactly("0", "12345", "4:0");
    }

    @Test
    @DisplayName("空输入：null/空白/纯分隔符均解析为空规则集（不跳过任何房屋）")
    void blankInput() {
        assertThat(HouseNumberSkipParser.parse(null).rules()).isEmpty();
        assertThat(HouseNumberSkipParser.parse("").rules()).isEmpty();
        assertThat(HouseNumberSkipParser.parse("  ").rules()).isEmpty();
        assertThat(HouseNumberSkipParser.parse(" , 、 ").rules()).isEmpty();
        assertThat(HouseNumberSkipParser.parse(null).matchesAny(1, 1, "101", "101")).isFalse();
    }

    @Test
    @DisplayName("多规则合并：任一规则命中即跳过（4:1,04 命中第 4 层 1 号与所有层的 4 号）")
    void multiRulesUnion() {
        Result result = HouseNumberSkipParser.parse("4:1,04");

        assertThat(result.rules()).hasSize(2);
        assertThat(result.matchesAny(4, 1, "401", "401")).isTrue();
        assertThat(result.matchesAny(2, 4, "204", "204")).isTrue();
        assertThat(result.matchesAny(2, 1, "201", "201")).isFalse();
        List<String> labels = result.rules().stream().map(Rule::label).toList();
        assertThat(labels).containsExactly("4 层 1 号", "每层 04 号");
    }
}
