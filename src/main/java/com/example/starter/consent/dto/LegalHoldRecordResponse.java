package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 保留权限只读查询响应：标记 LEGAL_HOLD 访问依据，
 * 不返回用于业务处理的授权可用状态（无 status 字段）。
 *
 * @param accessBasis 访问依据，固定为 LEGAL_HOLD
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       记录所属授权代次
 * @param recordKey   记录键
 * @param payload     记录内容（合成字符串）
 */
public record LegalHoldRecordResponse(
        String accessBasis,
        String subjectKey,
        Purpose purpose,
        int epoch,
        String recordKey,
        String payload) {
}
