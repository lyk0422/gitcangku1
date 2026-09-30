package com.example.starter.billing.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单条业务链汇总视图：承诺、两类读数的最新版本、奖励电量与净结算额。
 *
 * @param offerId          邀约 ID
 * @param userId           用户 ID
 * @param committedKwh     承诺减少用电量，单位 kWh；尚无承诺为 null
 * @param baselineVersion  当前最新基线读数版本；无读数为 null
 * @param baselineKwh      当前最新基线电量，单位 kWh；无读数为 null
 * @param actualVersion    当前最新实际读数版本；无读数为 null
 * @param actualKwh        当前最新实际电量，单位 kWh；无读数为 null
 * @param rewardKwh        最新版本口径的奖励电量，单位 kWh；尚未结算为 null
 * @param netSettlementFen 全部账目金额之和（首次结算与历次差额累加），单位分，可为负；无账目为 null
 * @param settlements      按入账顺序排列的全部账目
 */
public record AccountSummaryResponse(
        String offerId,
        String userId,
        BigDecimal committedKwh,
        Integer baselineVersion,
        BigDecimal baselineKwh,
        Integer actualVersion,
        BigDecimal actualKwh,
        BigDecimal rewardKwh,
        Long netSettlementFen,
        List<SettlementResponse> settlements) {
}
