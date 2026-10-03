package com.example.starter.curtailment.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理：业务异常按携带状态返回，参数校验失败返回 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 业务异常。 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiDtos.ErrorResponse(exception.code(), exception.getMessage(), exception.problems()));
    }

    /** Bean Validation 参数校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiDtos.ErrorResponse("VALIDATION_FAILED", message, List.of()));
    }

    /** 请求报文无法解析（如时间格式错误）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiDtos.ErrorResponse> handleNotReadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiDtos.ErrorResponse("BAD_REQUEST", "请求报文无法解析", List.of()));
    }
}