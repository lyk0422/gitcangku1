package com.example.starter.blind.web;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.dto.ErrorBody;
import com.example.starter.blind.service.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理；错误信息不包含处理代码或席位序号。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorBody(ex.getStatus().value(), ex.getStatus().getReasonPhrase(),
                        ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "请求参数不合法";
        return body(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, "请求体格式不正确");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorBody> handleMissingHeader(MissingRequestHeaderException ex) {
        String message = IdempotencyService.HEADER_REQUEST_ID.equals(ex.getHeaderName())
                ? "写操作必须携带全局唯一 X-Request-Id 请求头"
                : "缺少必需请求头: " + ex.getHeaderName();
        return body(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseEntity<ErrorBody> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(status.value(), status.getReasonPhrase(),
                        status.getReasonPhrase(), message));
    }
}
