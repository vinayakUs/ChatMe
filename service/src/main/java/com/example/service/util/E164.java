package com.example.service.util;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

import com.example.service.exceptions.ImpossiblePhoneNumberException;
import com.example.service.exceptions.NonNormalizedPhoneNumberException;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * Annotation that hold or return String value that is a valid
 * E164-normalized phone no
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.FIELD })
@Constraint(validatedBy = { E164.Validator.class })
@Documented
public @interface E164 {

    String message() default "value is not a valid E164 number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /**
     * Validator
     */
    public class Validator implements ConstraintValidator<E164, String> {

        @Override
        public boolean isValid(final String value, final ConstraintValidatorContext context) {

            if (Objects.isNull(value)) {
                return true;
            }
            if (!value.startsWith("+")) {
                return false;
            }
            try {
                Util.isNormalizedNumberRequired(value);
            } catch (ImpossiblePhoneNumberException | NonNormalizedPhoneNumberException ex) {
                return false;
            }
            return true;
        }
    }

}