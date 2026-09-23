package com.example.starter.restitution.domain;

/**
 * 裁决冻结的批准快照行。
 */
public record FrozenApprovalRow(String claimKey, long evidenceVersion, String reviewer, long createdAt) {
}
