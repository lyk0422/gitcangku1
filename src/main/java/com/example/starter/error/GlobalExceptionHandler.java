package com.example.starter.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常映射：业务异常按携带状态码返回；参数校验/请求解析失败返回 400；其余异常返回 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorBody> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.status())
                .body(new ErrorBody(ex.status().value(), ex.getMessage(), LocalDateTime.now(), null));
    }

    @ExceptionHandler(ItemValidationException.class)
    public ResponseEntity<ErrorBody> handleItemValidation(ItemValidationException ex) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorBody(422, ex.getMessage(), LocalDateTime.now(), ex.errors()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .collect(Collectors.joining(", ", "参数非法: ", ""));
        return ResponseEntity.badRequest()
                .body(new ErrorBody(400, message, LocalDateTime.now(), null));
    }

    @ExceptionHandler({MissingRequestHeaderException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorBody> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorBody(400, "参数非法: " + ex.getMessage(), LocalDateTime.now(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleOther(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorBody(500, "服务内部错误", LocalDateTime.now(), null));
    }

    /**
     * 统一错误响应体。
     *
     * @param status    HTTP 状态码
     * @param message   错误描述
     * @param timestamp 发生时间（Asia/Shanghai）
     * @param errors    逐项非法原因；仅 422 批量入库校验失败时非空
     */
    public record ErrorBody(int status, String message, LocalDateTime timestamp,
                            List<ItemValidationException.ItemError> errors) {
    }
}
