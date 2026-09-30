package com.example.starter.api.dto;

/**
 * 锁文件中单个名称对应的精确版本。
 *
 * @param riskLevel   锁定时该制品版本的风险级别：NONE/LOW/MEDIUM/HIGH/CRITICAL（历史锁保留当时值）
 * @param exceptionId 该 HIGH 及以上风险版本使用的豁免申请 ID；无风险或无需豁免为 null
 */
public record LockEntryResponse(String name, int version, String riskLevel, Long exceptionId) {
}
