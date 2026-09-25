package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 批次查询返回的记录视图。
 *
 * @param purpose   用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch     记录所属授权代次
 * @param recordKey 记录键
 * @param payload   记录内容（合成字符串）
 */
public record DelegateRecordView(
        Purpose purpose,
        int epoch,
        String recordKey,
        String payload) {
}
