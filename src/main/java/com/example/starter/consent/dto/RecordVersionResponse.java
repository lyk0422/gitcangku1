package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 记录版本响应：更正结果与历史读取均返回指定版本的完整内容。
 *
 * @param subjectKey    主体标识（合成字符串）
 * @param purpose       用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch         记录所属授权代次
 * @param recordKey     记录键
 * @param recordVersion 记录版本，从 1 开始，每次更正加一
 * @param payload       该版本的完整记录内容（合成字符串）
 */
public record RecordVersionResponse(String subjectKey, Purpose purpose, int epoch, String recordKey,
                                    int recordVersion, String payload) {
}
