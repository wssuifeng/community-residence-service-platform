package com.community.residence.log.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作留痕注解（R47）：标注在写操作 Service 方法上，由 OperationLogAspect
 * 在方法成功返回后写 sys_operation_log。仅追加、不修改业务行为；
 * 日志写入失败不影响业务（异常吞掉记 warn，与「留痕不阻断业务」口径一致）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 操作类型：CREATE / UPDATE / DELETE / STATUS（状态流转）/ REVIEW（审核）/ DISPOSAL（处置）等 */
    String operationType();

    /** 对象类型：COMMUNITY / BUILDING / HOUSE / RESIDENT / WORK_ORDER / NOTICE 等（表级语义） */
    String targetType();

    /**
     * 目标对象 ID 的 SpEL 表达式（参数名引用），如 "#id"、"#dto.housingId"、"#result.id"；
     * 留空则不记目标 ID（如无主键语义的导入操作）
     */
    String targetId() default "";

    /** 所属社区 ID 的 SpEL 表达式；留空则记当前用户绑定社区（无绑定则空） */
    String communityId() default "";

    /** 日志内容描述（支持 SpEL 模板，如 "'更新社区：' + #dto.name"）；留空则拼方法签名 */
    String content() default "";
}
