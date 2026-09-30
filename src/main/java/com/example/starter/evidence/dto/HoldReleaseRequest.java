package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 冻结解除请求。commandKey 同时作为解除申请业务键 releaseRequestId，全局唯一；
 * 解除原因非空，操作人（X-Actor-Id）必须不同于冻结创建人。
 *
 * @param commandKey 幂等命令键（即解除 requestId）
 * @param reason      解除原因，非空；解除只影响其后的销毁申请
 */
public record HoldReleaseRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
