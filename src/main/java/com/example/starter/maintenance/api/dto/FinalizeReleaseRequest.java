package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 整令解除请求：要求该隔离令的所有锁定点均已逐一解除，否则返回 422（部分解除）。
 * 整令解除成功后设备可重新提交新增运行读数。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致返回 409
 * @param operatorId       解除操作人
 * @param reason           整令解除原因（必填），随令固化
 */
public record FinalizeReleaseRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotBlank @Size(max = 512) String reason) {
}
