package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 清除响应：返回实际物理清除的记录数；重复清除同一代次返回 0。
 *
 * @param subjectKey        主体标识（合成字符串）
 * @param purpose           用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch             被清除的授权代次
 * @param purgedRecordCount 本次物理清除的记录数
 */
public record PurgeResponse(
        String subjectKey,
        Purpose purpose,
        int epoch,
        long purgedRecordCount) {
}
