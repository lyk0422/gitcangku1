package com.example.starter.consent.dto;

/**
 * 批次查询门禁阻断明细：按主体稳定列出阻断原因。
 *
 * @param subjectKey 被阻断的主体标识
 * @param reason     阻断原因：NO_ACTIVE_GRANT 无有效授权代次 /
 *                   ATTESTATION_MISSING 缺少生效证明 / ATTESTATION_EXPIRED 证明已到期
 */
public record ViolationDetail(String subjectKey, String reason) {
}
