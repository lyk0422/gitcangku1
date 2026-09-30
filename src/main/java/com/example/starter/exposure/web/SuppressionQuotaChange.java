package com.example.starter.exposure.web;

import java.time.LocalDate;

/**
 * 抑制导致的额度变化视图：某公告某 UTC 日因取消预占而返还的两级额度次数。
 *
 * @param campaignId       公告编号
 * @param utcDate          额度所属 UTC 日，格式 yyyy-MM-dd
 * @param releasedTotal    返还的公告当日总额度次数，单位次
 * @param releasedVisitor  返还的该访客当日额度次数，单位次
 */
public record SuppressionQuotaChange(
        String campaignId,
        LocalDate utcDate,
        int releasedTotal,
        int releasedVisitor
) {
}
