package com.valui.admin.exception;

import com.valui.common.dto.ErrorResponse;
import com.valui.common.exception.ValuiException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValuiException.class)
    public ResponseEntity<ErrorResponse> handleValui(ValuiException ex, HttpServletRequest req) {
        log.warn("ValuiException [{}] at {}: {}", ex.getHttpStatus(), req.getRequestURI(), ex.getMessage());
        return respond(ex.getHttpStatus(), ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest req) {
        log.debug("EntityNotFound at {}: {}", req.getRequestURI(), ex.getMessage());
        return respond(404, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
        log.debug("Validation error at {}: {}", req.getRequestURI(), detail);
        return respond(400, detail, req.getRequestURI());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        String detail = ex.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + ": " + v.getMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse(ex.getMessage());
        log.debug("Constraint violation at {}: {}", req.getRequestURI(), detail);
        return respond(400, detail, req.getRequestURI());
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex, HttpServletRequest req) {
        log.debug("ValidationException at {}: {}", req.getRequestURI(), ex.getMessage());
        return respond(400, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.debug("Method not supported at {} [{}]: {}", req.getRequestURI(), req.getMethod(), ex.getMessage());
        return respond(405, ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Access denied at {} [{}]: {}", req.getRequestURI(), req.getMethod(), ex.getMessage());
        return respond(403, "Access denied", req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error at {} [{}]", req.getRequestURI(), req.getMethod(), ex);
        return respond(500, "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> respond(int status, String message, String path) {
        HttpStatus httpStatus = HttpStatus.resolve(status);
        String error = httpStatus != null ? httpStatus.getReasonPhrase() : "Error";
        return ResponseEntity.status(status).body(ErrorResponse.of(status, error, message, path));
    }
}
