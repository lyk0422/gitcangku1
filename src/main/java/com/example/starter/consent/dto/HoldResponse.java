package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.HoldStatus;
import com.example.starter.consent.Purpose;

/**
 * 保留冻结响应：返回冻结键、所属代次、事由、到期时刻与当前状态。
 *
 * @param holdKey    冻结键（同一 epoch 同一冻结键只允许一个生效冻结）
 * @param subjectKey 主体标识
 * @param purpose    用途
 * @param epoch      被冻结的授权代次
 * @param reason     法定事由
 * @param createdBy  创建人（保留角色）
 * @param expiresAt  UTC 到期时刻
 * @param status     行状态：ACTIVE 生效中 / RELEASED 已人工解除
 * @param effective  是否仍具保留效力：status=ACTIVE 且按业务时钟尚未到期
 * @param createdAt  创建时间（UTC）
 */
public record HoldResponse(
        String holdKey,
        String subjectKey,
        Purpose purpose,
        int epoch,
        String reason,
        String createdBy,
        Instant expiresAt,
        HoldStatus status,
        boolean effective,
        Instant createdAt) {
}
