package com.example.starter.observation;

import java.time.Instant;

/**
 * 分析冻结的单条引用快照：生成分析时从被引用版本复制，之后不随源观测修订而变化。
 *
 * @param observationId 分钟观测记录唯一标识
 * @param version       引用的版本号（生成时的当前版本）
 * @param minuteUtc     该版本的观测时刻（UTC 整分钟）
 * @param reading       该版本的观测读数（十进制字符串原文）
 */
public record AnalysisReference(
        String observationId,
        int version,
        Instant minuteUtc,
        String reading) {
}
