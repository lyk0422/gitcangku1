package com.example.starter.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 试剂瓶：接收时登记批号、容量与供应商到期时刻（UTC），首次开封后固定开封时刻。
 * 实际截止取供应商效期与开封 30 天效期两者较早者，边界左闭右开。
 */
public record ReagentBottle(
        long id,
        String batchNo,
        BigDecimal capacityMl,
        BigDecimal remainingMl,
        Instant supplierExpiryAt,
        Instant openedAt,
        BottleStatus status,
        String quarantineReason,
        Instant quarantinedAt,
        Instant createdAt) {

    /** 开封后固定使用期限：30 天。 */
    public static final Duration OPEN_VALIDITY = Duration.ofDays(30);

    /**
     * 实际截止时刻（UTC）：未开封时仅受供应商效期约束；开封后取两者较早者。
     */
    public Instant effectiveDeadline() {
        if (openedAt == null) {
            return supplierExpiryAt;
        }
        Instant openDeadline = openedAt.plus(OPEN_VALIDITY);
        return supplierExpiryAt.isBefore(openDeadline) ? supplierExpiryAt : openDeadline;
    }

    /**
     * 实际截止时刻的取值依据；未开封时只能是供应商效期。
     */
    public DeadlineBasis deadlineBasis() {
        if (openedAt == null) {
            return DeadlineBasis.SUPPLIER_EXPIRY;
        }
        Instant openDeadline = openedAt.plus(OPEN_VALIDITY);
        return supplierExpiryAt.isBefore(openDeadline)
                ? DeadlineBasis.SUPPLIER_EXPIRY : DeadlineBasis.OPEN_EXPIRY;
    }
}