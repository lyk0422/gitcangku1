package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建保留冻结请求。案件级挂在案件上，证物级挂在单件证物上；到期时刻按 UTC 解释。
 *
 * @param requestId  冻结请求幂等键，全局唯一；同键换对象或到期时间返回 409
 * @param expiresUtc UTC 到期时刻，须晚于当前；冻结窗口左闭右开，恰好到期后不再阻断销毁
 * @param reason     冻结原因，非空
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull LocalDateTime expiresUtc,
        @NotBlank @Size(max = 512) String reason) {
}
