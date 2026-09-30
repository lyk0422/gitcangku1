package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 授权方可查的读取审计项：记录每一次依据承诺成功读取的接收方、用途、代次与时刻。
 *
 * <p>撤回承诺（随授权代次撤回）不删除历史读取记录。
 *
 * @param id           审计记录编号
 * @param subjectKey   授权主体标识
 * @param recipientKey 执行读取的接收方标识
 * @param purpose      读取用途
 * @param epoch        读取所依据的授权代次
 * @param recordKey    被读取的记录键
 * @param readAt       UTC 读取时刻
 */
public record ReadAuditView(long id, String subjectKey, String recipientKey, Purpose purpose, int epoch,
                            String recordKey, Instant readAt) {
}
