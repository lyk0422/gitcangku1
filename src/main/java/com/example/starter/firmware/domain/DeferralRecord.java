package com.example.starter.firmware.domain;

/**
 * 设备拉取顺延统计，同设备同发布单最多一条，人工恢复不清零。
 *
 * @param id                 顺延记录ID
 * @param releaseId          所属发布单ID
 * @param deviceId           设备ID
 * @param deferCount         累计被顺延次数
 * @param lastDeferredAtUtc  最近顺延时刻，UTC，ISO-8601格式
 */
public record DeferralRecord(long id, long releaseId, String deviceId, int deferCount,
                             String lastDeferredAtUtc) {
}
