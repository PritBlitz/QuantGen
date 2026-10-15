package com.quantgen.auth.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * F-AUTH-1: at least 8 characters with an uppercase letter, a lowercase letter, a digit and a symbol.
 * Capped at 72 UTF-8 bytes, the most BCrypt can hash.
 */
@Documented
@Constraint(validatedBy = StrongPassword.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "must be 8-72 characters with an uppercase letter, a lowercase letter, a digit and a symbol";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int MAX_BYTES = 72;

    class Validator implements ConstraintValidator<StrongPassword, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true; // @NotNull reports missing values
            }
            if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                return false;
            }
            boolean upper = false;
            boolean lower = false;
            boolean digit = false;
            boolean symbol = false;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isUpperCase(c)) {
                    upper = true;
                } else if (Character.isLowerCase(c)) {
                    lower = true;
                } else if (Character.isDigit(c)) {
                    digit = true;
                } else if (!Character.isWhitespace(c) && !Character.isISOControl(c)) {
                    symbol = true;
                }
            }
            return upper && lower && digit && symbol;
        }
    }
}
