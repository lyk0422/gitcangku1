package com.example.starter.web.dto;

import java.math.BigDecimal;

/**
 * 账目视图；展示首笔/更正差额、旧新版本与更正缘由。
 */
public record LedgerEntryView(
        Long id,
        String entryType,
        Integer oldVersion,
        int newVersion,
        BigDecimal rewardKwh,
        long amountFen,
        String reason
) {
}
