package com.example.starter.billing.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 结算账目视图；ADJUSTMENT 行同时给出旧版本与新版本及更正缘由。
 *
 * @param settlementId            账目 ID
 * @param type                    INITIAL 首次结算 / ADJUSTMENT 更正差额
 * @param baselineVersion         本次固化的基线读数版本（新版本）
 * @param actualVersion           本次固化的实际读数版本（新版本）
 * @param previousBaselineVersion ADJUSTMENT 时的旧基线版本；INITIAL 为 null
 * @param previousActualVersion   ADJUSTMENT 时的旧实际版本；INITIAL 为 null
 * @param baselineReadingId       固化的基线读数行 ID
 * @param actualReadingId         固化的实际读数行 ID
 * @param committedKwh            承诺减少用电量，单位 kWh
 * @param baselineKwh             基线电量，单位 kWh
 * @param actualKwh               实际电量，单位 kWh
 * @param reductionKwh            实际压降电量（基线减实际，不为负），单位 kWh
 * @param rewardKwh               奖励电量，单位 kWh，按 0.1 kWh 向下截取
 * @param amountFen               本次入账金额，单位分；ADJUSTMENT 为差额，可为负
 * @param totalAmountFen          本次版本重算的完整奖励金额，单位分
 * @param correctedSettlementId   ADJUSTMENT 指向的原账目 ID；INITIAL 为 null
 * @param correctionReason        更正缘由；INITIAL 为 null
 * @param requestId               结算幂等键
 * @param createdAt               入账时间（UTC）
 */
public record SettlementResponse(
        Long settlementId,
        String type,
        int baselineVersion,
        int actualVersion,
        Integer previousBaselineVersion,
        Integer previousActualVersion,
        Long baselineReadingId,
        Long actualReadingId,
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
