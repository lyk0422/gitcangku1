package com.example.starter.restitution.dto;

/**
 * 批准视图。
 *
 * @param reviewer        评审人
 * @param evidenceVersion 批准所针对的证据版本
 * @param createdAt       批准时间（epoch 毫秒）
 */
public record ApprovalView(String reviewer, long evidenceVersion, long createdAt) {
}
