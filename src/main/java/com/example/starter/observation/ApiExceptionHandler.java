package com.example.starter.observation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常与参数校验失败统一转为 ErrorResponse。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 业务异常：404/409/410 等，携带冲突字段与当前版本（如有）。
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        HttpStatus status = ex.status();
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                ex.getMessage(), ex.conflictFields(), ex.currentVersion());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 请求体校验失败：400，汇总字段错误信息。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        ErrorResponse body = new ErrorResponse(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), message, null, null);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 请求体无法解析（JSON 格式错误或枚举取值非法）：400。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        ErrorResponse body = new ErrorResponse(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), "request body is malformed or has invalid values", null, null);
        return ResponseEntity.badRequest().body(body);
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": "
                + (error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage());
    }
}
