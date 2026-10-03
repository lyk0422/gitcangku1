package com.example.starter.web;

import com.example.starter.exception.BusinessException;
import com.example.starter.exception.ConflictException;
import com.example.starter.exception.NotFoundException;
import com.example.starter.web.dto.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射：业务拒绝 422，幂等冲突 409，不存在 404，参数校验 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务拒绝（缺测、终测过高、他批占用、数量超限等），响应携带电芯编号。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> business(BusinessException e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse(e.getMessage(), e.cellCodes()));
    }

    /**
     * 幂等冲突：同一 requestId 异参重发。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(e.getMessage(), List.of()));
    }

    /**
     * 资源不存在。
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(e.getMessage(), List.of()));
    }

    /**
     * 请求参数校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("请求参数不合法");
        return ResponseEntity.badRequest().body(new ErrorResponse(message, List.of()));
    }
}