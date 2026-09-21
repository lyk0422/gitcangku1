package com.example.starter.plan.web;

import com.example.starter.plan.web.dto.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 统一错误映射：业务异常按自身状态码返回；参数绑定/校验失败返回 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getField)
                .map(field -> "参数非法: " + field)
                .orElse("参数非法");
        return badRequest(message);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return badRequest("请求参数非法: " + e.getClass().getSimpleName());
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_ARGUMENT", message, List.of()));
    }
}
