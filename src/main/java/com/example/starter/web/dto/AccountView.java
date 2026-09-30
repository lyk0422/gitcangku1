package com.example.starter.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 单用户单邀约全链视图：承诺、读数版本、首次结算、账目流水与净结算额。
 */
public record AccountView(
        Long invitationId,
        String userId,
        CommitmentView commitment,
        List<ReadingVersionView> readings,
        SettlementView settlement,
        long netAmountFen
) {
}
