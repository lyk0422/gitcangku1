package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 记录响应：返回记录所属主体、用途、代次、记录键与内容。
 *
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      记录所属授权代次
 * @param recordKey  记录键
 * @param payload    记录内容（合成字符串）
 */
public record RecordResponse(String subjectKey, Purpose purpose, int epoch, String recordKey, String payload) {
}
