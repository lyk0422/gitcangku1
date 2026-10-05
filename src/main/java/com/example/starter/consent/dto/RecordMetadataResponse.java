package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 记录元数据响应：只返回代次与版本号，不返回 payload；撤回后仍可查询。
 *
 * @param subjectKey    主体标识（合成字符串）
 * @param purpose       用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch         记录所属授权代次
 * @param recordKey     记录键
 * @param recordVersion 当前记录版本，从 1 开始
 */
public record RecordMetadataResponse(String subjectKey, Purpose purpose, int epoch, String recordKey,
                                     int recordVersion) {
}
