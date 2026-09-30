package com.example.starter.consent.dto;

import java.time.Instant;

/**
 * 接收方读取审计条目：只记录读取依据与结果，不包含记录内容，撤回承诺也不删除历史记录。
 *
 * @param id                    审计记录自增主键
 * @param subjectKey            授权主体标识
 * @param recipientKey         发起读取的接收方标识
 * @param purpose               本次读取用途
 * @param epoch                 读取所依据的授权代次；拒绝且无授权代次时为 null
 * @param recordKey             请求读取的记录键
 * @param result                结果：SUCCESS 成功 / DENIED 被拒绝
 * @param denyReason            拒绝原因码，成功为 null
 * @param attestationExpiresAt  成功读取所依据承诺的到期时刻（UTC），拒绝为 null
 * @param createdAt             审计时间（UTC）
 */
public record ReadAuditResponse(
        long id,
        String subjectKey,
        String recipientKey,
        String purpose,
        Integer epoch,
        String recordKey,
        String result,
        String denyReason,
        Instant attestationExpiresAt,
        Instant createdAt) {
}
