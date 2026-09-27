package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 数据清除响应。
 *
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey   记录键
 * @param erasedCount 实际清除的记录数（跨全部代次）
 * @param erasedAt    清除时间（UTC）
 */
public record EraseResponse(String subjectKey, Purpose purpose, String recordKey,
                            int erasedCount, Instant erasedAt) {
}
