package com.example.starter.germination.web;

import com.example.starter.germination.service.BusinessRejectedException;
import com.example.starter.germination.service.CachedResponseException;
import com.example.starter.germination.service.ConflictException;
import com.example.starter.germination.service.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * 业务异常到 HTTP 状态码的映射。
 */
@RestControllerAdvice
public class GerminationExceptionHandler {

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessRejectedException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getReason().name(), ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorBody> handleConflict(ConflictException ex) {
        return build(ex.getStatus(), ex.getReason().name(), ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorBody> handleNotFound(NotFoundException ex) {
        return build(ex.getStatus(), ex.getReason().name(), ex.getMessage());
    }

    @ExceptionHandler(CachedResponseException.class)
    public ResponseEntity<ErrorBody> handleCached(CachedResponseException ex) {
        HttpStatus status;
        try {
            status = HttpStatus.valueOf(ex.getHttpStatus());
        } catch (IllegalArgumentException e) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        }
        return build(status, ex.getReason().name(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数校验未通过");
    }

    private ResponseEntity<ErrorBody> build(HttpStatus status, String reason, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(status.value(), reason, message, Instant.now()));
    }
}
