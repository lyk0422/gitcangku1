package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 保留冻结创建请求（案件级与单件证物级共用）。
 * commandKey 同时作为冻结申请业务键 holdRequestId，全局唯一。
 *
 * @param commandKey 幂等命令键（即冻结 requestId）
 * @param expiresAt  UTC 到期时刻，须晚于服务端当前 UTC 时刻；窗口左闭右开
 * @param reason     创建原因，非空
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull LocalDateTime expiresAt,
        @NotBlank @Size(max = 512) String reason) {
}
