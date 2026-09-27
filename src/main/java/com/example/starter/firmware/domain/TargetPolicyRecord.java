package com.example.starter.firmware.domain;

/**
 * 发布单目标标签策略的固化记录（创建时写入，后续不可改）。
 *
 * @param releaseId       所属发布单ID
 * @param policy          目标标签策略
 * @param tagDictVersion  解析目标快照时的标签字典版本
 * @param resolvedAtUtc   目标快照解析时刻，UTC，ISO-8601格式
 * @param targetCount     目标快照设备数
 */
public record TargetPolicyRecord(long releaseId, TargetPolicy policy, long tagDictVersion,
                                 String resolvedAtUtc, int targetCount) {
}
