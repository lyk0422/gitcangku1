package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 批量隔离中的单设备项：携带该设备的期望版本与完整锁定点集合。
 *
 * @param equipmentId      设备标识
 * @param expectedVersion  该设备期望版本号
 * @param lockPoints       该设备完整锁定点集合，至少 1 个
 */
public record BatchIsolationItemRequest(
        @NotBlank String equipmentId,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<@Valid LockPointSpec> lockPoints) {
}
