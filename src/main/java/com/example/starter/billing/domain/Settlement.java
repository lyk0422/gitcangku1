package com.example.starter.billing.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算账目行，一经写入不可变；更正只追加 ADJUSTMENT 差额行。
 *
 * @param id                  账目自增主键
 * @param offerId             邀约 ID
 * @param userId              用户 ID
 * @param type                账目类型：INITIAL 首次结算 / ADJUSTMENT 更正差额
 * @param baselineReadingId   固化的基线读数行 ID
 * @param actualReadingId     固化的实际读数行 ID
 * @param baselineVersion     固化的基线读数版本
 * @param actualVersion       固化的实际读数版本
 * @param committedKwh        承诺减少用电量，单位 kWh
 * @param baselineKwh         基线电量，单位 kWh
 * @param actualKwh           实际电量，单位 kWh
 * @param reductionKwh        基线减实际的实际压降，单位 kWh（不为负，负值按 0）
 * @param rewardKwh           奖励电量，单位 kWh，按 0.1 kWh 向下截取
 * @param amountFen           入账金额，单位分（整数）；ADJUSTMENT 为新旧金额差额，可为负
 * @param totalAmountFen      该版本重算后的完整奖励金额（单位分），ADJUSTMENT 时用于核对
 * @param correctedSettlementId  ADJUSTMENT 行指向被更正的原账目 ID；INITIAL 为 null
 * @param correctionReason    更正缘由；INITIAL 为 null
 * @param requestId           结算请求幂等键
 * @param createdAt           入账时间（UTC）
 */
public record Settlement(
        Long id,
        String offerId,
        String userId,
        SettlementType type,
        Long baselineReadingId,
        Long actualReadingId,
        int baselineVersion,
        int actualVersion,
        BigDecimal committedKwh,
        BigDecimal baselineKwh,
        BigDecimal actualKwh,
        BigDecimal reductionKwh,
        BigDecimal rewardKwh,
        long amountFen,
        long totalAmountFen,
        Long correctedSettlementId,
        String correctionReason,
        String requestId,
        Instant createdAt) {
}
