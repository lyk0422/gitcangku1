package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 批量隔离多设备请求中的单个设备项：携带该设备的完整锁定点集合。
 *
 * @param equipmentId      被隔离设备标识
 * @param orderId          该设备隔离令标识，全局唯一
 * @param expectedVersion  设备期望版本号
 * @param lockPoints       完整锁定点集合（至少一个），重复返回 422
 */
public record BatchIsolationItem(
        @NotBlank String equipmentId,
        @NotBlank String orderId,
        @NotNull Long expectedVersion,
        @NotEmpty @Valid List<LockPointInput> lockPoints) {
}
