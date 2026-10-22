package com.community.residence.common.resident.service;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Excel另存的表头编码不应被错误归类为字段模型不一致。只用合成姓名。 */
class ResidentCsvEncodingTest {
    private static final String CSV = "小区ID,姓名,手机号,证件号,用户名,邮箱,房屋ID,关系类型,入住日期,备注\n2,合成居民,13999991001,,中文账户,,,,,\n";
    private List<String[]> parse(byte[] bytes) {
        ResidentService service = mock(ResidentService.class, CALLS_REAL_METHODS);
        return ReflectionTestUtils.invokeMethod(service, "parseCsv", new ByteArrayInputStream(bytes));
    }
    @Test void gbkFromExcelKeepsChineseHeaderAndData() { assertThat(parse(CSV.getBytes(Charset.forName("GB18030"))).get(0)).containsExactly("2", "合成居民", "13999991001", "", "中文账户", "", "", "", "", ""); }
    @Test void unicodeExcelBomIsSupported() { assertThat(parse(CSV.getBytes(StandardCharsets.UTF_16)).get(0)[1]).isEqualTo("合成居民"); }
    @Test void utf8BomStillWorks() { assertThat(parse(("\uFEFF" + CSV).getBytes(StandardCharsets.UTF_8))).hasSize(1); }
    @Test void properlyQuotedHeadersAreNotWrongHeaders() { String quoted = CSV.replace(CSV.split("\n")[0], "\"小区ID\",\"姓名\",\"手机号\",\"证件号\",\"用户名\",\"邮箱\",\"房屋ID\",\"关系类型\",\"入住日期\",\"备注\""); assertThat(parse(quoted.getBytes(StandardCharsets.UTF_8))).hasSize(1); }
}
