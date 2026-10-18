package com.community.residence.common.auth.service;

import com.community.residence.common.auth.entity.SysOperationLog;
import com.community.residence.common.auth.vo.OperationLogVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** DEF-139：日志展示契约不能依赖数据库没有保存的字段。 */
class OperationLogContractTest {
    @Test void listAndDetailProvideDesignedAliasesFromStoredMetadata() {
        SysOperationLog row = new SysOperationLog(); row.setId(66L); row.setOperatorId(2L);
        row.setOperatorType("ADMIN"); row.setOperationType("CREATE"); row.setTargetType("HOUSE");
        row.setContent("{\"action\":\"新建房屋101\"}"); row.setIp("127.0.0.1");
        OperationLogVO vo = OperationLogVO.from(row);
        assertEquals("CREATE", vo.getAction()); assertEquals("COMMUNITY", vo.getModule());
        assertEquals("新建房屋101", vo.getDescription()); assertEquals("127.0.0.1", vo.getIpAddress());
        assertEquals(row.getContent(), vo.getContent());
        assertNull(vo.getRequestParams(), "未采集的原始请求参数不得伪造");
    }
    @Test void oldContentFallsBackWithoutBreakingDetail() {
        SysOperationLog row = new SysOperationLog(); row.setContent("旧版本纯文本留痕"); row.setTargetType("HOUSING");
        assertEquals("旧版本纯文本留痕", OperationLogVO.from(row).getDescription());
        assertEquals("HOUSING", OperationLogVO.from(row).getModule());
    }
}
