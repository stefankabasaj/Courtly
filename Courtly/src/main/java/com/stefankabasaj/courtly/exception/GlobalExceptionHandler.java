package com.stefankabasaj.courtly.exception;

import com.stefankabasaj.courtly.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // broken business rule (cancelling too late, inactive court, etc)
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(
            BusinessException ex, HttpServletRequest req) {
        log.warn("Business rule violation at {}: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req);
    }

    // generic message on purpose - don't tell an attacker which half was wrong
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(HttpServletRequest req) {
        return error(HttpStatus.UNAUTHORIZED, "Unauthorized", "Invalid email or password", req);
    }

    // authenticated but wrong role
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(HttpServletRequest req) {
        return error(HttpStatus.FORBIDDEN, "Forbidden",
                "You don't have permission to access this resource", req);
    }

    // not found, or hidden because it isn't yours
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            ResourceNotFoundException ex, HttpServletRequest req) {
        log.warn("Not found at {}: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req);
    }

    // duplicate, or the slot's gone
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(
            ConflictException ex, HttpServletRequest req) {
        log.warn("Conflict at {}: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req);
    }

    // @Valid failed on the body - we return the per-field messages
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value",
                        (first, second) -> first
                ));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.withFields(422, "Validation Failed",
                        "Request validation failed", req.getRequestURI(), fieldErrors));
    }

    // @Min/@Max on query params throw a different exception than body validation
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage(),
                        (first, second) -> first
                ));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.withFields(422, "Validation Failed",
                        "Parameter validation failed", req.getRequestURI(), fieldErrors));
    }

    // e.g. passing "abc" where a UUID is expected
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String msg = "Invalid value '" + ex.getValue() + "' for parameter '" + ex.getName() + "'.";
        return error(HttpStatus.BAD_REQUEST, "Bad Request", msg, req);
    }

    // two requests raced past the app-level checks and the database stopped the
    // second one (double booking, duplicate email, ...)
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation at {}: {}", req.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data. Please check it and try again.", req);
    }

    // someone else changed the same row between our read and our write (@Version)
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest req) {
        log.warn("Optimistic lock failure at {}: {}", req.getRequestURI(), ex.getMessage());
        return error(HttpStatus.CONFLICT, "Conflict",
                "This resource was modified by someone else. Please reload and try again.", req);
    }

    // malformed JSON, wrong field types, unknown enum value, ...
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request",
                "Request body is missing or malformed.", req);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest req) {
        return error(HttpStatus.BAD_REQUEST, "Bad Request",
                "Required parameter '" + ex.getParameterName() + "' is missing.", req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed",
                "HTTP method " + ex.getMethod() + " is not supported for this endpoint.", req);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type",
                "Content type is not supported. Use application/json.", req);
    }

    // unknown URL
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            NoResourceFoundException ex, HttpServletRequest req) {
        return error(HttpStatus.NOT_FOUND, "Not Found", "Resource not found.", req);
    }

    // catch-all. full trace to the logs, bland message to the client
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest req) {
        log.error("Unexpected error at {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "Something went wrong. Please try again later.", req);
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status, String error, String message, HttpServletRequest req) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), error, message, req.getRequestURI()));
    }
}
