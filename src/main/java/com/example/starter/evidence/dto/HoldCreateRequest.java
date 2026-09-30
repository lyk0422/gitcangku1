package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建保留冻结请求。适用于案件级与证物级冻结。
 *
 * @param commandKey 幂等命令键（同一冻结 requestId）
 * @param holdKey    冻结业务键，全局唯一
 * @param expireAt   UTC 到期时刻，须晚于服务端当前时刻
 * @param reason     创建原因，非空
 */
public record HoldCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String holdKey,
        @NotNull LocalDateTime expireAt,
        @NotBlank @Size(max = 512) String reason) {
}
