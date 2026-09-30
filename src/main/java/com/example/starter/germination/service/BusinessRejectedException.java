package com.example.starter.germination.service;

/**
 * 业务规则被违反时抛出；映射为 HTTP 422。
 */
public class BusinessRejectedException extends RuntimeException {

    private final RejectReason reason;

    public BusinessRejectedException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
