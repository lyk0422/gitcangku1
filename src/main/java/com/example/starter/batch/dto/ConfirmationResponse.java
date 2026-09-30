package com.example.starter.batch.dto;

import com.example.starter.batch.ApprovalRole;

import java.time.Instant;

/**
 * 偏差处置确认响应。两名不同角色（QUALITY/OPERATIONS）且不同操作人
 * 针对同一偏差版本各确认一次后偏差解除；note 为空表示未填写备注。
 */
public record ConfirmationResponse(
        String deviationKey,
        int version,
        String actorId,
        ApprovalRole role,
        String note,
        Instant createdAt
) {
}
