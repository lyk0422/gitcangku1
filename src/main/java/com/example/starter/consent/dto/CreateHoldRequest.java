package com.example.starter.consent.dto;

import java.time.Instant;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保留冻结创建请求：对有效或已撤回代次创建法定保留冻结。
 *
 * @param requestId   幂等请求标识，同一 requestId 相同参数重试返回原结果
 * @param holdKey     冻结标识，全局唯一
 * @param subjectKey  主体标识（合成字符串）
 * @param purpose     用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param epoch       被冻结的授权代次，从 1 开始
 * @param legalReason 法定事由，同一 epoch 同一事由只允许一个生效冻结
 * @param createdBy   创建人（保留角色操作人标识）
 * @param expiresAt   UTC 到期时刻（ISO-8601），必须晚于当前时刻
 */
public record CreateHoldRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String holdKey,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch,
        @NotBlank @Size(max = 128) String legalReason,
        @NotBlank @Size(max = 128) String createdBy,
        @NotNull Instant expiresAt) {
}
