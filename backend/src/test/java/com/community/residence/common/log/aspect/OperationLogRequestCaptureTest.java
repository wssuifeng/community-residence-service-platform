package com.community.residence.common.log.aspect;
import com.community.residence.common.auth.entity.SysOperationLog;
import com.community.residence.common.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.auth.vo.OperationLogVO;
import com.community.residence.common.log.annotation.OperationLog;
import com.community.residence.common.resident.dto.AdminCreateResidentDTO;
import com.community.residence.common.resident.dto.MoveOutDTO;
import com.community.residence.common.workorder.dto.AssignWorkOrderDTO;
import com.community.residence.common.workorder.dto.SaveStaffCapabilityDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** DEF-139：只采集有界业务参数，不能把凭据、自由文本或个人资料写入日志。 */
class OperationLogRequestCaptureTest {
    private final ObjectMapper json = new ObjectMapper();
    public static class Fixture {
        @OperationLog(operationType="UPDATE", targetType="WORK_ORDER", content="'安全参数测试'")
        public void assign(Long id, AssignWorkOrderDTO dto) { }
        @OperationLog(operationType="CREATE", targetType="RESIDENT", content="'创建居民'")
        public void resident(AdminCreateResidentDTO dto) { }
        @OperationLog(operationType="STATUS", targetType="RESIDENCE_RELATION", content="'迁出'")
        public void move(Long id, MoveOutDTO dto) { }
        @OperationLog(operationType="UPDATE", targetType="COMMUNITY", content="'绑定社区'")
        public void bind(Long id, SaveStaffCapabilityDTO dto) { }
        @OperationLog(operationType="UPDATE", targetType="COMMUNITY", content="'未知对象'")
        public void unknown(Long id, Object dto) { }
    }
    @AfterEach void clean() { RequestContextHolder.resetRequestAttributes(); }
    private SysOperationLog capture(String name, Class<?>[] types, boolean http, Object... args) throws Exception {
        if (http) RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var mapper = mock(SysOperationLogMapper.class);
        var aspect = new OperationLogAspect(mapper);
        Method method = Fixture.class.getMethod(name, types);
        JoinPoint point = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(point.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(point.getArgs()).thenReturn(args);
        aspect.recordOperation(point, method.getAnnotation(OperationLog.class), null);
        var captor = org.mockito.ArgumentCaptor.forClass(SysOperationLog.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }
    @Test void assignmentCapturesDesignedIdsAndExposesDetail() throws Exception {
        var dto = new AssignWorkOrderDTO(); dto.setAssigneeId(19L); dto.setRemark("不要存储这个自由文本");
        var row = capture("assign", new Class<?>[]{Long.class, AssignWorkOrderDTO.class}, true, 7L, dto);
        var params = json.readTree(row.getContent()).path("requestParams");
        assertThat(params.path("id").asLong()).isEqualTo(7);
        assertThat(params.path("assigneeId").asLong()).isEqualTo(19);
        assertThat(params.has("remark")).isFalse();
        assertThat(json.readTree(OperationLogVO.from(row).getRequestParams())).isEqualTo(params);
        assertThat(OperationLogVO.from(row).getDescription()).isEqualTo("安全参数测试");
    }
    @Test void residentPersonalDetailsAreNeverCaptured() throws Exception {
        var dto = new AdminCreateResidentDTO(); dto.setCommunityId(9L); dto.setPhone("13900001234");
        dto.setRealName("合成测试姓名"); dto.setIdCard("110101200001010019"); dto.setUsername("private_login");
        var row = capture("resident", new Class<?>[]{AdminCreateResidentDTO.class}, true, dto);
        assertThat(row.getContent()).contains("communityId").doesNotContain("13900001234", "合成测试姓名", "110101200001010019", "private_login");
        assertThat(json.readTree(row.getContent()).path("requestParams").size()).isEqualTo(1);
    }
    @Test void moveOutStoresDateNotReason() throws Exception {
        var dto = new MoveOutDTO(); dto.setMoveOutDate(LocalDate.of(2026,10,11)); dto.setReason("自由文本中可能有私钥或联系方式");
        var row = capture("move", new Class<?>[]{Long.class, MoveOutDTO.class}, true, 8L, dto);
        assertThat(json.readTree(row.getContent()).path("requestParams").path("moveOutDate").asText()).isEqualTo("2026-10-11");
        assertThat(row.getContent()).doesNotContain("自由文本中可能");
    }
    @Test void unknownObjectsDoNotInvokeGettersOrToString() throws Exception {
        Object unknown = new Object() { @Override public String toString() { throw new AssertionError("must not invoke"); } };
        var row = capture("unknown", new Class<?>[]{Long.class, Object.class}, true, 7L, unknown);
        assertThat(json.readTree(row.getContent()).path("requestParams").size()).isEqualTo(1);
    }
    @Test void largeIdListsHaveExplicitBound() throws Exception {
        var dto = new SaveStaffCapabilityDTO(); dto.setCommunityIds(LongStream.rangeClosed(1, 10000).boxed().toList());
        var row = capture("bind", new Class<?>[]{Long.class, SaveStaffCapabilityDTO.class}, true, 7L, dto);
        var params = json.readTree(row.getContent()).path("requestParams");
        assertThat(params.path("communityIds").size()).isEqualTo(20);
        assertThat(params.path("_truncated").asBoolean()).isTrue();
        assertThat(row.getContent().length()).isLessThan(4096);
    }
    @Test void nonHttpOperationsDoNotPretendToHaveRequests() throws Exception {
        var dto = new AssignWorkOrderDTO(); dto.setAssigneeId(19L);
        var row = capture("assign", new Class<?>[]{Long.class, AssignWorkOrderDTO.class}, false, 7L, dto);
        assertThat(json.readTree(row.getContent()).has("requestParams")).isFalse();
        assertThat(OperationLogVO.from(row).getRequestParams()).isNull();
    }
}

