package com.example.starter.observation.geofence;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 地理围栏模块异常处理：语义校验失败统一转为 422 及明细列表。
 */
@RestControllerAdvice
public class GeofenceExceptionHandler {

    /**
     * 422：经纬度范围、围栏边界、生效区间、批量数量等语义校验失败。
     */
    @ExceptionHandler(GeofenceValidationException.class)
    public ResponseEntity<GeofenceErrorResponse> handleValidation(GeofenceValidationException ex) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        GeofenceErrorResponse body = new GeofenceErrorResponse(
                status.value(), status.getReasonPhrase(), ex.getMessage(), ex.details());
        return ResponseEntity.status(status).body(body);
    }
}
