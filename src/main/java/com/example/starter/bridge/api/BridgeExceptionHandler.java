package com.example.starter.bridge.api;

import com.example.starter.bridge.BridgeApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 响应的映射。
 */
@RestControllerAdvice
public class BridgeExceptionHandler {

    /**
     * 业务异常按携带状态码返回。
     */
    @ExceptionHandler(BridgeApiException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BridgeApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorResponse(ex.code(), ex.getMessage()));
    }

    /**
     * 参数校验失败返回 400。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst()
                .orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", message));
    }
}