package com.example.starter.cue.web;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.example.starter.cue.exception.CueConflictException;
import com.example.starter.cue.exception.CueNotFoundException;
import com.example.starter.cue.exception.CueValidationException;
import com.example.starter.cue.support.ClockConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常到 HTTP 状态码：校验失败 400、不存在 404、业务冲突 409。
 */
@RestControllerAdvice
public class CueExceptionHandler {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /**
     * Bean Validation 注解校验失败（请求体字段约束）。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        return build(HttpStatus.BAD_REQUEST, errors);
    }

    /**
     * 业务整版本/回报内容校验失败。
     */
    @ExceptionHandler(CueValidationException.class)
    public ResponseEntity<ApiError> handleBusinessValidation(CueValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getErrors());
    }

    /**
     * 请求体不可读（JSON 格式错误等）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, List.of("请求体格式错误或缺失"));
    }

    /**
     * 资源不存在。
     */
    @ExceptionHandler(CueNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CueNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, List.of(ex.getMessage()));
    }

    /**
     * 业务冲突：跳号/重复回报、异参重放、状态不允许、并发下唯一约束冲突等。
     */
    @ExceptionHandler(CueConflictException.class)
    public ResponseEntity<ApiError> handleConflict(CueConflictException ex) {
        return build(HttpStatus.CONFLICT, List.of(ex.getMessage()));
    }

    /**
     * 请求参数类型不匹配（如路径变量非数字）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, List.of("请求参数不合法"));
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private ResponseEntity<ApiError> build(HttpStatus status, List<String> errors) {
        ApiError body = new ApiError(
                status.value(),
                status.getReasonPhrase(),
                List.copyOf(errors),
                        LocalDateTime.now(ClockConfig.BUSINESS_ZONE).format(TIME_FORMATTER));
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 统一错误响应体。
     *
     * @param status   HTTP 状态码
     * @param error    状态短语
     * @param messages 全部错误原因
     * @param timestamp 响应时间（Asia/Shanghai，ISO 本地日期时间）
     */
    public record ApiError(int status, String error, List<String> messages, String timestamp) {
    }
}
