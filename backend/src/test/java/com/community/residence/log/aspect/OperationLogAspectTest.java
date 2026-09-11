package com.community.residence.log.aspect;

import com.community.residence.auth.entity.SysOperationLog;
import com.community.residence.auth.mapper.SysOperationLogMapper;
import com.community.residence.common.context.SecurityUtils;
import com.community.residence.common.context.UserContext;
import com.community.residence.log.annotation.OperationLog;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEF-014 修复回归（R47 关键变更留痕）：@OperationLog 切面写入 sys_operation_log——
 * 操作人/对象/内容解析、社区归属推导、未登录 SYSTEM 口径、留痕失败不阻断业务。
 * 纯单测无 Spring 容器（@Aspect 代理不生效），直接以 JoinPoint 驱动切面方法。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("操作日志切面单元测试（DEF-014）")
class OperationLogAspectTest {

    /** 测试用 Service：承载注解方法（注解元数据经反射读取） */
    static class FakeService {
        @OperationLog(operationType = "CREATE", targetType = "COMMUNITY",
                targetId = "#result.id", content = "'创建社区：' + #dto.name")
        public String create(Dto dto) {
            return "9";
        }

        @OperationLog(operationType = "STATUS", targetType = "WORK_ORDER",
                targetId = "#id", communityId = "#dto.communityId", content = "'工单流转 ' + #id")
        public void transition(Long id, Dto dto) {
        }

        @OperationLog(operationType = "UPDATE", targetType = "NOTICE")
        public void noExpression() {
        }
    }

    /** 测试参数 DTO（public 字段保证 SpEL 属性访问） */
    public static class Dto {
        public String name = "阳光花园";
        public Long communityId = 1L;
    }

    @Mock
    private SysOperationLogMapper operationLogMapper;

    @InjectMocks
    private OperationLogAspect aspect;

    private final FakeService service = new FakeService();

    private JoinPoint joinPoint(String methodName, Object... args) throws Exception {
        Method method = FakeService.class.getMethod(methodName,
                java.util.Arrays.stream(args).map(Object::getClass).toArray(Class[]::new));
        JoinPoint jp = mock(JoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        lenient().when(jp.getSignature()).thenReturn(signature);
        lenient().when(signature.getMethod()).thenReturn(method);
        lenient().when(jp.getArgs()).thenReturn(args);
        lenient().when(signature.toShortString())
                .thenReturn("FakeService." + methodName + "(..)");
        return jp;
    }

    /* AfterReturning 语义：返回值在切面执行时以 result 变量注入 —— joinPoint mock 无法携带，
       故 targetId 引用 #result 的用例改由方法参数承载（同解析路径覆盖 SpEL 主干） */
    @BeforeEach
    void initNames() {
        // DefaultParameterNameDiscoverer 需编译期 -parameters 支持（Boot 默认开启），无额外准备
    }

    @Test
    @DisplayName("注解方法成功后写日志：SpEL 解析 #dto/#id 全链路（操作人取安全上下文）")
    void recordCreate_withSpel() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(1L)));

            JoinPoint jp = joinPoint("transition", 5L, new Dto());
            aspect.recordOperation(jp,
                    methodAnnotation("transition", Long.class, Dto.class));

            ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
            verify(operationLogMapper).insert(captor.capture());
            SysOperationLog entry = captor.getValue();
            assertThat(entry.getOperatorId()).isEqualTo(9L);
            assertThat(entry.getOperatorType()).isEqualTo("ADMIN");
            assertThat(entry.getOperationType()).isEqualTo("STATUS");
            assertThat(entry.getTargetType()).isEqualTo("WORK_ORDER");
            assertThat(entry.getTargetId()).isEqualTo(5L);
            assertThat(entry.getContent()).isEqualTo("{\"action\":\"工单流转 5\"}");
            assertThat(entry.getCommunityId()).isEqualTo(1L);
            assertThat(entry.getCreatedAt()).isNotNull();
        }
    }

    @Test
    @DisplayName("无表达式：targetId/communityId 为空、内容回退方法签名、ADMIN 单绑定推导社区")
    void noExpression_fallbacks() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser)
                    .thenReturn(new UserContext(9L, "admin1", "ADMIN", Set.of(3L)));

            JoinPoint jp = joinPoint("noExpression");
            aspect.recordOperation(jp, methodAnnotation("noExpression"));

            ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
            verify(operationLogMapper).insert(captor.capture());
            SysOperationLog entry = captor.getValue();
            assertThat(entry.getTargetId()).isNull();
            /* 无显式表达式 → ADMIN 单绑定社区推导 */
            assertThat(entry.getCommunityId()).isEqualTo(3L);
            assertThat(entry.getContent()).contains("noExpression");
        }
    }

    @Test
    @DisplayName("未登录（定时任务链路）：operatorType 记 SYSTEM，operatorId 为空")
    void unauthenticated_systemOperatorType() throws Exception {
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(null);

            JoinPoint jp = joinPoint("noExpression");
            aspect.recordOperation(jp, methodAnnotation("noExpression"));

            ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
            verify(operationLogMapper).insert(captor.capture());
            SysOperationLog entry = captor.getValue();
            assertThat(entry.getOperatorId()).isNull();
            assertThat(entry.getOperatorType()).isEqualTo("SYSTEM");
        }
    }

    @Test
    @DisplayName("日志写入失败不阻断业务（R47 留痕为旁路审计）")
    void insertFailure_doesNotPropagate() throws Exception {
        when(operationLogMapper.insert(any(SysOperationLog.class)))
                .thenThrow(new RuntimeException("db down"));
        try (MockedStatic<SecurityUtils> mocked = mockStatic(SecurityUtils.class)) {
            mocked.when(SecurityUtils::getUser).thenReturn(null);

            JoinPoint jp = joinPoint("noExpression");
            assertThatCode(() ->
                    aspect.recordOperation(jp, methodAnnotation("noExpression")))
                    .doesNotThrowAnyException();
            verify(operationLogMapper, times(1)).insert(any(SysOperationLog.class));
        }
    }

    private OperationLog methodAnnotation(String methodName, Class<?>... paramTypes)
            throws Exception {
        return FakeService.class.getMethod(methodName, paramTypes)
                .getAnnotation(OperationLog.class);
    }
}
