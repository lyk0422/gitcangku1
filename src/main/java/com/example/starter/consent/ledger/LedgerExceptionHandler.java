package com.example.starter.consent.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 账本域全局异常处理：业务异常输出稳定业务码与实际值/要求值/差额/定位明细。
 */
@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerApiException.class)
    public ResponseEntity<LedgerErrorResponse> handleApi(LedgerApiException ex) {
        return ResponseEntity.status(ex.getStatus()).body(LedgerErrorResponse.of(ex));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<LedgerErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new LedgerErrorResponse("INVALID_ARGUMENT", "请求参数错误: " + ex.getMessage(),
                        null, null, null, null));
    }
}
