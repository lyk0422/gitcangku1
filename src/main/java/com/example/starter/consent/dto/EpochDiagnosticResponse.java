package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;

/**
 * 代次驻留诊断项（只读）：返回授权代次状态、驻留区域与授权版本。
 *
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch          授权代次
 * @param status         状态：ACTIVE 有效 / REVOKED 已撤回
 * @param regionCode     驻留区域代码；未绑定区域为 null
 * @param consentVersion 授权版本：直接授权为 1，迁移产生的新代为源代版本+1
 * @param totalRecords   代次内记录总数
 * @param erasedRecords  代次内已清除记录数
 */
public record EpochDiagnosticResponse(String subjectKey, Purpose purpose, int epoch, String status,
                                      String regionCode, int consentVersion,
                                      int totalRecords, int erasedRecords, Instant latestRecordCreatedAt) {
}
