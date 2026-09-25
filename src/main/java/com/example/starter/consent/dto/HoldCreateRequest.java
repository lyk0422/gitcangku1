package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保留冻结创建请求：对有效或已撤回 epoch 创建保留冻结。
 *
 * <p>操作人身份与角色由请求头 X-Actor-Id / X-Actor-Role 提供，须为保留角色。
 *
 * @param requestId  幂等请求标识
 * @param holdKey    冻结键（法定事由标识），全局唯一；同一 epoch 同一冻结键只允许一个生效冻结
 * @param subjectKey 主体标识（合成字符串）
 * @param purpose    用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch      被冻结的授权代次，从 1 开始
 * @param reason     法定事由说明
 * @param expiresAt  UTC 到期时刻，必须晚于当前时刻
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String holdKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 512) String reason,
        @NotNull Instant expiresAt) {
}
