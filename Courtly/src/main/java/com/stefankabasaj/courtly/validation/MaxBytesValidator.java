package com.stefankabasaj.courtly.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxBytesValidator implements ConstraintValidator<MaxBytes, String> {

    private int maxBytes;

    @Override
    public void initialize(MaxBytes annotation) {
        this.maxBytes = annotation.value();
    }

    // null is @NotBlank's job
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || utf8Length(value) <= maxBytes;
    }

    public static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
