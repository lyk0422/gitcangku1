package com.example.starter.exposure.web;

import org.springframework.http.HttpStatus;

/**
 * 额度不足类业务异常（HTTP 429）。携带机器可区分原因码与实际值/要求值/差额/下一可用时刻，
 * 便于调用方区分公告日总额、访客日上限、活动小时耗尽或小时未配置等场景。
 */
public class QuotaExceededException extends ApiException {

    /** 429 的机器可区分原因码。 */
    public enum Reason {
        /** 公告当日总额度已耗尽。 */
        CAMPAIGN_DAILY_TOTAL_EXHAUSTED,
        /** 访客当日上限已耗尽。 */
        VISITOR_DAILY_CAP_EXHAUSTED,
        /** 公告归属活动，但当前 UTC 日未配置 24 小时预算；未配置不得申请。 */
        ACTIVITY_DAY_NOT_CONFIGURED,
        /** 活动当前小时名额已耗尽。 */
        ACTIVITY_HOUR_EXHAUSTED
    }

    private final Reason reason;
    private final Long actualValue;
    private final Long requiredValue;
    private final Long nextAvailableAtUtc;

    public QuotaExceededException(Reason reason, String message,
                                  Long actualValue, Long requiredValue, Long nextAvailableAtUtc) {
        super(HttpStatus.TOO_MANY_REQUESTS, message);
        this.reason = reason;
        this.actualValue = actualValue;
        this.requiredValue = requiredValue;
        this.nextAvailableAtUtc = nextAvailableAtUtc;
    }

    public Reason getReason() {
        return reason;
    }

    public Long getActualValue() {
        return actualValue;
    }

    public Long getRequiredValue() {
        return requiredValue;
    }

    /** 差额：要求值 - 实际值；任一为空返回 null。 */
    public Long getDifferenceValue() {
        if (actualValue == null || requiredValue == null) {
            return null;
        }
        return requiredValue - actualValue;
    }

    public Long getNextAvailableAtUtc() {
        return nextAvailableAtUtc;
    }
}
