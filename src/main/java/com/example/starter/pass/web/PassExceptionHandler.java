package com.example.starter.pass.web;

import com.example.starter.pass.api.ErrorBody;
import com.example.starter.pass.domain.BusinessRuleException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 业务异常到 HTTP 响应的统一映射；不向最终用户暴露堆栈。
 */
@RestControllerAdvice
public class PassExceptionHandler {

    /** 可预期规则拒绝：携带可区分原因码与对应状态码。 */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorBody> handleBusiness(BusinessRuleException ex) {
        return ResponseEntity.status(ex.reason().status())
                .body(new ErrorBody(ex.reason().code(), ex.getMessage()));
    }

    /** Bean 校验失败统一按 400 处理。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorBody("INVALID_REQUEST", message));
    }

    /** 唯一约束等完整性冲突按 409 处理。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorBody> handleIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("REQUEST_PARAM_MISMATCH", "conflicting idempotent request"));
    }
}
