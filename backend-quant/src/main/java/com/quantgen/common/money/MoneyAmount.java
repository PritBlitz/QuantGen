package com.quantgen.common.money;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.math.BigDecimal;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * A customer-entered INR amount: at most 2 decimal places, between {@code min} and {@code max}
 * inclusive. Null is valid (combine with {@code @NotNull}).
 */
@Documented
@Constraint(validatedBy = MoneyAmount.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MoneyAmount {

    String min() default "0.01";

    String max();

    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<MoneyAmount, BigDecimal> {

        private BigDecimal min;
        private BigDecimal max;

        @Override
        public void initialize(MoneyAmount annotation) {
            this.min = new BigDecimal(annotation.min());
            this.max = new BigDecimal(annotation.max());
        }

        @Override
        public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            String message = null;
            if (value.stripTrailingZeros().scale() > Money.DISPLAY_SCALE) {
                message = "must have at most 2 decimal places";
            } else if (value.compareTo(min) < 0) {
                message = "must be at least " + Money.toJson(min);
            } else if (value.compareTo(max) > 0) {
                message = "must be at most " + Money.toJson(max);
            }
            if (message == null) {
                return true;
            }
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
            return false;
        }
    }
}
