package com.example.starter.site.dto;

import java.time.Instant;

/**
 * 许可批准明细视图。
 */
public record ApprovalView(String approver, int seqNo, Instant approvedAt) {
}
