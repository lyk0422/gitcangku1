package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.CampaignWindowVersion;

import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * 公告归因窗口版本视图（不可变历史）。
 *
 * @param version                   窗口版本号，从 1 起
 * @param attributionWindowMinutes  该版本 UTC 归因窗口分钟数（要求值），1～1440
 * @param operatorId                生效操作者；公告创建产生的 V1 为 null
 * @param createdAtUtc              版本生效时刻，epoch 毫秒，UTC
 * @param effectiveUtcDate          生效时刻对应的 UTC 日（诊断辅助字段，格式 yyyy-MM-dd）
 */
public record WindowVersionResponse(
        int version,
        int attributionWindowMinutes,
        String operatorId,
        long createdAtUtc,
        LocalDate effectiveUtcDate
) {
    public static WindowVersionResponse from(CampaignWindowVersion v) {
        return new WindowVersionResponse(
                v.version(),
                v.attributionWindowMinutes(),
                v.operatorId(),
                v.createdAtUtc(),
                java.time.Instant.ofEpochMilli(v.createdAtUtc()).atOffset(ZoneOffset.UTC).toLocalDate());
    }
}
