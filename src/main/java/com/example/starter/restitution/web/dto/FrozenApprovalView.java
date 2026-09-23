package com.example.starter.restitution.web.dto;

/**
 * 冻结批准信息快照视图。
 */
public record FrozenApprovalView(
        String claimKey,
        String reviewer,
        long evidenceVersion
) {
}
