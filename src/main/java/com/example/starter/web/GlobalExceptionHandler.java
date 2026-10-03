package com.example.starter.web;

import java.time.Clock;
import java.time.Instant;

import com.example.starter.repo.RejectionRepository;
import com.example.starter.service.BusinessException;
import com.example.starter.web.dto.ApiDtos.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应；业务拒绝同步写入拒绝事件表（独立提交，业务回滚不影响审计）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final RejectionRepository rejectionRepository;
    private final Clock clock;

    public GlobalExceptionHandler(RejectionRepository rejectionRepository, Clock clock) {
        this.rejectionRepository = rejectionRepository;
        this.clock = clock;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        if (e.getOperation() != null) {
            try {
                rejectionRepository.insert(e.getTagCode(), e.getAnimalCode(), e.getOperation(),
                        e.getCode() + ": " + e.getMessage(), Instant.now(clock));
            } catch (Exception insertError) {
                log.warn("拒绝事件写入失败: {}", insertError.getMessage());
            }
        }
        return ResponseEntity.status(e.getStatus())
                .body(new ErrorResponse(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", message));
    }
}