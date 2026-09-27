package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 创建（单设备）能源隔离令请求。隔离令创建后为 PENDING，须由 ISOLATOR、VERIFIER 两个不同
 * 维护角色分别确认全部锁定点后生效；锁定点集合创建时固化。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致返回 409
 * @param operatorId       创建操作人
 * @param lockPoints       完整锁定点集合，至少 1 个；同令内标识重复返回 422
 */
public record CreateIsolationRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotEmpty @Valid List<@Valid LockPointSpec> lockPoints) {
}
