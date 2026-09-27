package com.example.starter.calibration.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：将业务异常与框架异常映射为可区分的 400/404/409/410/422/500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<ErrorResponse> handleBatchRejected(BatchRejectedException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage(), ex.getFailures()));
    }

    @ExceptionHandler(OutlierBlockedException.class)
    public ResponseEntity<BlockedErrorResponse> handleOutlierBlocked(OutlierBlockedException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new BlockedErrorResponse(ex.getCode(), ex.getMessage(),
                        ex.getPendingOutliers(), ex.getRequiredPending(), ex.getRemaining()));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("BAD_REQUEST", "请求参数非法: " + ex.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", "资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务内部错误"));
    }

    /**
     * 放行被未裁决离群值阻断的错误响应体。
     *
     * @param code            业务错误码
     * @param message         错误描述
     * @param pendingOutliers 实际待裁决离群记录数
     * @param requiredPending 要求值：放行前允许的待裁决数（0）
     * @param remaining       差额：仍需裁决的离群记录数
     */
    public record BlockedErrorResponse(String code, String message, int pendingOutliers,
                                       int requiredPending, int remaining) {
    }
}
