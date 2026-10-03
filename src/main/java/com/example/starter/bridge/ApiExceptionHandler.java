package com.example.starter.bridge;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.starter.bridge.dto.ErrorResponse;

/**
 * 许可调度异常到 HTTP 响应的映射：业务冲突 409，许可不存在 404，参数校验失败 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务冲突：超重、时间倒退、窗口重叠、幂等冲突、状态竞争等。 */
    @ExceptionHandler(PermitConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(PermitConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    /** 许可不存在。 */
    @ExceptionHandler(PermitNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PermitNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PERMIT_NOT_FOUND", exception.getMessage()));
    }

    /** 请求参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_FAILED", "请求参数校验失败"));
    }
}