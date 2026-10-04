package com.example.starter.blind.dto;

/**
 * 样本记录视图（协调员自查本人提交记录）：只含摘要与采集时刻，不含真实样本。
 *
 * @param sampleNo           样本编号，全实验唯一
 * @param phase              阶段 PRE / POST
 * @param digest             样本摘要
 * @param collectedAt        采集时刻，Unix 毫秒，UTC
 * @param status             ACTIVE=当前有效；SUPERSEDED=已被更正替代
 * @param supersedesSampleNo 被替代的最近有效样本编号；null 表示首份登记
 * @param createdAt          登记时间，Unix 毫秒，UTC
 */
public record SampleRecordView(
        String sampleNo,
        String phase,
        String digest,
        long collectedAt,
        String status,
        String supersedesSampleNo,
        long createdAt
) {
}