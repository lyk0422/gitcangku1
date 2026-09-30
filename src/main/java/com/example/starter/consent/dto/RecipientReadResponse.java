package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 接收方读取响应：只返回原有授权范围内的记录数据，并携带本次读取所依据的承诺与审计信息。
 *
 * @param subjectKey  授权主体标识
 * @param recipientKey 读取数据的接收方标识
 * @param purpose     读取用途，与承诺用途一致
 * @param epoch       依据的授权代次
 * @param recordKey   记录键
 * @param payload     记录内容（仅授权范围内数据）
 * @param auditId     本次读取审计记录编号
 * @param readAt      UTC 读取时刻
 */
public record RecipientReadResponse(String subjectKey, String recipientKey, Purpose purpose, int epoch,
                                    String recordKey, String payload, long auditId, Instant readAt) {
}
