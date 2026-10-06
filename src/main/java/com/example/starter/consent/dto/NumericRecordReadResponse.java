package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 数值记录读取响应：含数值载荷，仅在当前授权有效时可读。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param value      数值载荷：0-1000 非负整数
 */
public record NumericRecordReadResponse(String subjectKey, Purpose purpose, int epoch, String recordKey, int value) {
}
