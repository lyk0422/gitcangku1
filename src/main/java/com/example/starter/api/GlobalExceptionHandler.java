package com.example.starter.api;

import com.example.starter.api.dto.Responses;
import com.example.starter.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理：统一输出 {code, message} 错误体。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Responses.ErrorView> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new Responses.ErrorView(e.code(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Responses.ErrorView> handleUnreadable(HttpMessageNotReadableException e) {
        return badRequest("INVALID_BODY", "请求体无法解析");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Responses.ErrorView> handleTypeMismatch(
            MethodArgumentTypeMismatchException e) {
        return badRequest("INVALID_PARAMETER", "参数格式错误: " + e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Responses.ErrorView> handleMissingParam(
            MissingServletRequestParameterException e) {
        return badRequest("INVALID_PARAMETER", "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Responses.ErrorView> handleOther(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new Responses.ErrorView("INTERNAL_ERROR", "服务内部错误"));
    }

    private ResponseEntity<Responses.ErrorView> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(new Responses.ErrorView(code, message));
    }
}
