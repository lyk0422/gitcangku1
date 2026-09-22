package com.example.starter.translation.web;

import com.example.starter.translation.dto.Dtos.ErrorView;
import com.example.starter.translation.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：输出统一错误体 {"code","message"}。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorView> handleApi(ApiException e) {
        return ResponseEntity.status(e.status()).body(new ErrorView(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorView> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorView("BAD_REQUEST", "缺少请求头: " + e.getHeaderName()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorView> handleBadRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorView("BAD_REQUEST", "请求格式错误"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorView> handleDuplicateKey(DuplicateKeyException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorView("VERSION_CONFLICT", "唯一约束冲突"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorView> handleOther(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorView("INTERNAL_ERROR", "服务内部错误"));
    }
}
