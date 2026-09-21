package com.example.starter.site.dto;

import java.time.Instant;
import java.util.List;

/**
 * 作业许可视图，含引用隔离键与批准明细。closedAt 为 null 表示尚未关闭。
 */
public record PermitView(
        String permitKey,
        String crewName,
        Instant workStartUtc,
        Instant workEndUtc,
        String applicant,
        String status,
        List<String> isolationKeys,
        List<ApprovalView> approvals,
        Instant createdAt,
        Instant closedAt) {
}
