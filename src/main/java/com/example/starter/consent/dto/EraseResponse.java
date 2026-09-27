package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 数据清除响应：返回被清除记录的定位与清除时间。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param erasedAt   清除时间（UTC）
 */
public record EraseResponse(String subjectKey, Purpose purpose, int epoch, String recordKey, Instant erasedAt) {
}
