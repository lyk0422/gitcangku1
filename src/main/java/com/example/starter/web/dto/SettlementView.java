package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算结果视图；amountFen 为本次结算金额，netAmountFen 为首笔与全部更正差额之和。
 */
public record SettlementView(
        Long settlementId,
        Long invitationId,
        String userId,
        BigDecimal committedReductionKwh,
        int readingVersion,
        BigDecimal rewardKwh,
        long amountFen,
        long netAmountFen,
        boolean replayed,
        List<LedgerEntryView> entries
) {
}
