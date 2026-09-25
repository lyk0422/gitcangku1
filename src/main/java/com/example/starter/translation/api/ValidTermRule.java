package com.example.starter.translation.api;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 文档术语规则跨字段校验：suppressed=false 时 requiredTranslation 必填；
 * suppressed=true 时必须不携带必译文本（抑制规则取消全局规则，自身不提供译文要求）。
 */
@Target({ElementType.TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = ValidTermRule.Validator.class)
public @interface ValidTermRule {

    String message() default "suppressed 规则不能携带 requiredTranslation，普通规则必须携带 requiredTranslation";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** 跨字段校验器。 */
    class Validator implements ConstraintValidator<ValidTermRule, ApiDtos.TermRuleInput> {

        @Override
        public boolean isValid(ApiDtos.TermRuleInput value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            boolean hasRequired = value.requiredTranslation() != null && !value.requiredTranslation().isBlank();
            return value.suppressed() != hasRequired;
        }
    }
}
