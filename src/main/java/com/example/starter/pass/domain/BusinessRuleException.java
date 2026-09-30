package com.example.starter.pass.domain;

/**
 * 可预期的业务规则异常，携带可区分的拒绝原因。
 */
public class BusinessRuleException extends RuntimeException {

    private final RejectReason reason;

    public BusinessRuleException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason reason() {
        return reason;
    }
}
