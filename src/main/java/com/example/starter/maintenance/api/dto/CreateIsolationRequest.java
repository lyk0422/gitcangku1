package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 创建单个设备能源隔离令请求。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param orderId          隔离令标识，全局唯一
 * @param createdBy        创建操作者标识（固化为业务证据）
 * @param expectedVersion  设备期望版本号，与当前版本不一致返回 409
 * @param lockPoints       完整锁定点集合（至少一个），集合内标识重复返回 422
 */
public record CreateIsolationRequest(
        @NotBlank String requestId,
        @NotBlank String orderId,
        @NotBlank String createdBy,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<LockPointInput> lockPoints) {
}
