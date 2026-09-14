package com.community.residence.community.service;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 批量创建逐行校验器（DEF-032）：批量 DTO 已去 @Valid 元素级联（整批 400
 * 使部分成功语义不可达），行对象校验移到服务层逐行执行——坏行收集首条
 * 违约提示逐行反馈，好行照常创建。对齐 R8 CSV 导入先例。
 */
@Component
public class RowValidator {

    private static final Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    /** 单行校验：通过返回 null，否则返回首条「提示」拼接（字段名不暴露给用户） */
    public static <T> String validate(T row) {
        Set<ConstraintViolation<T>> violations = VALIDATOR.validate(row);
        return violations.isEmpty() ? null
                : violations.iterator().next().getMessage();
    }
}
