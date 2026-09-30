package com.example.starter.germination.service;

import org.springframework.http.HttpStatus;

/**
 * 请求与当前资源状态冲突（如失效证书放行、幂等异参、批次已放行）；映射为 HTTP 409。
 */
public class ConflictException extends RuntimeException {

    private final RejectReason reason;
    private final HttpStatus status;

    public ConflictException(RejectReason reason, String message) {
        this(reason, message, HttpStatus.CONFLICT);
    }

    public ConflictException(RejectReason reason, String message, HttpStatus status) {
        super(message);
        this.reason = reason;
        this.status = status;
    }

    public RejectReason getReason() {
        return reason;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
