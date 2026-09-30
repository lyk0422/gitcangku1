package com.example.starter.web.error;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 将业务异常与参数校验异常映射为统一错误响应，不向调用方泄漏堆栈。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(ex.errorCode().httpStatus())
                .body(new ErrorResponse(ex.errorCode().name(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String detail = fieldError == null
                ? "请求参数校验失败"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus())
                .body(new ErrorResponse(ErrorCode.VALIDATION_FAILED.name(), detail));
    }
}
