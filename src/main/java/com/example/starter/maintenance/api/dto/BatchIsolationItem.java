package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 批量隔离中的单台设备条目：每台设备都须给出完整锁定点集合。
 *
 * @param equipmentId      设备唯一标识
 * @param expectedVersion  该设备期望版本号
 * @param lockPoints       完整锁定点集合，至少一个，标识重复返回 422
 */
public record BatchIsolationItem(
        @NotBlank String equipmentId,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<LockPointInput> lockPoints) {
}
