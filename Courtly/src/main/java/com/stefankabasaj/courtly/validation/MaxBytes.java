package com.stefankabasaj.courtly.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Max length in UTF-8 bytes, not characters. @Size counts characters, but
 * BCrypt's limit is 72 bytes - and "č", "ш" or an emoji take 2-4 bytes each,
 * so a password can pass @Size(max = 72) and still be too long for BCrypt.
 */
@Documented
@Constraint(validatedBy = MaxBytesValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface MaxBytes {

    int value();

    String message() default "Too long";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
