package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 单设备创建能源隔离令请求：创建时必须给出完整锁定点集合，两名不同维护角色随后分别确认。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param expectedVersion  设备期望版本号，与当前版本不一致时返回 409
 * @param operatorId       创建操作人
 * @param lockPoints       完整锁定点集合，至少一个，标识重复返回 422
 */
public record CreateIsolationRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String operatorId,
        @NotEmpty @Valid List<LockPointInput> lockPoints) {
}
