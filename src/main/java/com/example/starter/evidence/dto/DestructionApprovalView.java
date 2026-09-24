package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 销毁令审批同意记录视图。
 *
 * @param approverId 同意的审批人（两名互不相同且都不同于提交人）
 * @param createdAt  同意时间（Asia/Shanghai）
 */
public record DestructionApprovalView(
        String approverId,
        LocalDateTime createdAt) {
}
