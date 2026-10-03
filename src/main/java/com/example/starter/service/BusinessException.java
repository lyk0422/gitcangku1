package com.example.starter.service;

import com.example.starter.model.RejectReason;
import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带可区分的拒绝原因码与 HTTP 状态。
 */
public class BusinessException extends RuntimeException {

    private final RejectReason reason;
    private final HttpStatus status;

    public BusinessException(RejectReason reason, HttpStatus status, String message) {
        super(message);
        this.reason = reason;
        this.status = status;
    }

    /** 以 409 构造业务拒绝。 */
    public static BusinessException conflict(RejectReason reason, String message) {
        return new BusinessException(reason, HttpStatus.CONFLICT, message);
    }

    /** 以 404 构造资源不存在。 */
    public static BusinessException notFound(String message) {
        return new BusinessException(RejectReason.NOT_FOUND, HttpStatus.NOT_FOUND, message);
    }

    public RejectReason reason() {
        return reason;
    }

    public HttpStatus status() {
        return status;
    }
}