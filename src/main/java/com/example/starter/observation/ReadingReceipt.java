package com.example.starter.observation;

import java.time.Instant;

/**
 * 源输入收件记录：对应 reading_receipt 表的一行。
 * 带量纲读数每次成功收件（创建/合并/解决）都另存原值、原始单位与精确规范值，
 * 即使规范值与当前等价、未增业务版本也不省掉收件溯源；失败请求随事务回滚不留收件。
 *
 * @param requestId         产生该收件的请求标识（幂等键，全局唯一）
 * @param observationId     观测记录唯一标识
 * @param operation         收件来源操作：CREATE / MERGE / RESOLVE
 * @param dimension         读数量纲：LENGTH / TEMPERATURE
 * @param originalReading   源输入原始读数（十进制原文，最多三位小数）
 * @param originalUnit      源输入原始单位：m / cm / C / K
 * @param normalizedReading 精确规范读数（长度 m、温度 C，十进制原文，不舍入）
 * @param operator          操作者标识；未提供时为 null
 * @param receivedAtUtc     收件时刻（UTC）
 */
public record ReadingReceipt(
        String requestId,
        String observationId,
        String operation,
        String dimension,
        String originalReading,
        String originalUnit,
        String normalizedReading,
        String operator,
        Instant receivedAtUtc) {
}
