package com.example.starter.consent;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * 批量拒绝异常：携带稳定业务码、HTTP 状态与逐对象拒绝原因，
 * 由全局异常处理器转换为带 reasons 列表的错误响应，整批不生效。
 */
public class BatchRejectionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<RejectionReason> reasons;

    public BatchRejectionException(HttpStatus status, String code, String message, List<RejectionReason> reasons) {
        super(message);
        this.status = status;
        this.code = code;
        this.reasons = List.copyOf(reasons);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<RejectionReason> getReasons() {
        return reasons;
    }
}
