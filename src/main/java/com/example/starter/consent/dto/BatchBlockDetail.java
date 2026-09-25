package com.example.starter.consent.dto;

/**
 * 批次查询阻断明细：稳定列出未通过门禁的主体与原因。
 *
 * @param subjectKey 主体标识
 * @param epoch      该主体当前授权代次；无授权时为 null
 * @param reason     稳定原因码：GRANT_NOT_FOUND / GRANT_REVOKED /
 *                   ATTESTATION_MISSING / ATTESTATION_EXPIRED / RECIPIENT_DISABLED
 */
public record BatchBlockDetail(String subjectKey, Integer epoch, String reason) {
}
