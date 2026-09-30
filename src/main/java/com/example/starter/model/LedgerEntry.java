package com.example.starter.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账目条目；首笔为全量奖励（FIRST），更正为新旧版本差额（CORRECTION，金额可为负）。
 *
 * @param id           账目 ID
 * @param invitationId 邀约 ID
 * @param userId       用户 ID
 * @param settlementId 关联的首次结算 ID
 * @param entryType    FIRST-首笔结算；CORRECTION-更正差额
 * @param oldVersion   更正前版本；首笔为 null
 * @param newVersion   本次入账所依据的读数版本
 * @param rewardKwh    本次账目奖励电量差（kWh，更正时可为负）
 * @param amountFen    本次入账金额（分；更正为差额，可为负）
 * @param reason       更正缘由；首笔为 null
 * @param createdAt    入账时间（UTC）
 */
public record LedgerEntry(
        Long id,
        Long invitationId,
        String userId,
        Long settlementId,
        String entryType,
        Integer oldVersion,
        int newVersion,
        BigDecimal rewardKwh,
        long amountFen,
        String reason,
        Instant createdAt
) {
    public static final String TYPE_FIRST = "FIRST";
    public static final String TYPE_CORRECTION = "CORRECTION";
}
