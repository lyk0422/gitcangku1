package com.example.starter.crosssection;

import com.example.starter.observation.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 断面模块异常处理：仅作用于断面控制器。
 * 题干规定断面场景缺字段一律 422（区别于通用模块参数校验的 400）；业务异常按其携带状态返回。
 */
@RestControllerAdvice(assignableTypes = CrossSectionController.class)
public class CrossSectionExceptionHandler {

    /**
     * 断面业务异常：404/409/422 等，携带冲突字段与当前版本（如有）。
     */
    @ExceptionHandler(CrossSectionApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(CrossSectionApiException ex) {
        HttpStatus status = ex.status();
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                ex.getMessage(), ex.conflictFields(), ex.currentVersion());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 请求体缺失或字段不合法：断面场景按题干统一 422。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(CrossSectionExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        return unprocessable(message);
    }

    /**
     * 路径变量/参数约束失败（如版本号小于 1）：同样 422。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        return unprocessable(ex.getMessage());
    }

    /**
     * 请求体整体缺失或 JSON 无法解析：断面场景按题干统一 422。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return unprocessable("request body is missing or unreadable");
    }

    private ResponseEntity<ErrorResponse> unprocessable(String message) {
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message, null, null));
    }

    private static String formatFieldError(FieldError error) {
        return error.getField() + ": "
                + (error.getDefaultMessage() == null ? "must not be missing" : error.getDefaultMessage());
    }
}
