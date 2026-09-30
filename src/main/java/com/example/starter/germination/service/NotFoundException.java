package com.example.starter.germination.service;

import org.springframework.http.HttpStatus;

/**
 * 资源不存在（批次/检测记录）；映射为 HTTP 404。
 */
public class NotFoundException extends RuntimeException {

    private final RejectReason reason;

    public NotFoundException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }

    public HttpStatus getStatus() {
        return HttpStatus.NOT_FOUND;
    }
}
