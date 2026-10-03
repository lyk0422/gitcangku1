package com.example.starter.web;

import com.example.starter.repo.RejectionLogRepository;
import com.example.starter.service.BusinessException;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：业务异常映射为 404/409/422，并在业务事务回滚后
 * 以独立写入记录拒绝日志，供时间线展示拒绝原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 错误响应体。
     *
     * @param error 稳定错误码
     * @param message 可读说明
     */
    public record ErrorResponse(String error, String message) {
    }

    private final RejectionLogRepository rejections;
    private final Clock clock;

    public GlobalExceptionHandler(RejectionLogRepository rejections, Clock clock) {
        this.rejections = rejections;
        this.clock = clock;
    }

    /**
     * 业务异常：记录拒绝原因后返回对应状态码。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        rejections.insert(e.operation(), e.tagCode(), e.animalCode(), e.stationCode(),
                e.code() + ": " + e.getMessage(), clock.instant());
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage()));
    }

    /**
     * 参数校验失败：返回 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", message));
    }
}