package com.example.starter.germination.service;

/**
 * 幂等重放命中首次失败响应时抛出；携带首次响应的 HTTP 状态码与拒绝原因，
 * 保证同参重放返回与首次完全一致的结果。
 */
public class CachedResponseException extends RuntimeException {

    private final int httpStatus;
    private final RejectReason reason;

    public CachedResponseException(int httpStatus, RejectReason reason, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.reason = reason;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public RejectReason getReason() {
        return reason;
    }
}
