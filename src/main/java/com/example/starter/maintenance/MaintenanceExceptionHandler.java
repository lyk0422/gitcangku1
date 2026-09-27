package com.example.starter.maintenance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 观测站维护业务异常处理：业务异常与参数校验失败统一转为 MaintenanceErrorResponse。
 */
@RestControllerAdvice(basePackages = "com.example.starter.maintenance")
public class MaintenanceExceptionHandler {

    /**
     * 业务异常：按异常携带的状态码与错误码响应。
     */
    @ExceptionHandler(MaintenanceApiException.class)
    public ResponseEntity<MaintenanceErrorResponse> handleApiException(MaintenanceApiException ex) {
        return ResponseEntity.status(ex.status()).body(new MaintenanceErrorResponse(
                ex.status().value(), ex.status().getReasonPhrase(), ex.errorCode(), ex.getMessage()));
    }
}
