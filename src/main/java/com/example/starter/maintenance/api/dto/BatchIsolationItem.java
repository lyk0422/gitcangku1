package com.example.starter.maintenance.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * 批量隔离中的单台设备项。
 *
 * @param equipmentId   目标设备标识（须存在且未停用）
 * @param orderId       该设备隔离令唯一标识（全局唯一）
 * @param lockPointIds  完整锁定点集合；不可为空、不可重复
 */
public record BatchIsolationItem(
        @NotBlank String equipmentId,
        @NotBlank String orderId,
        @NotEmpty @Valid List<@NotBlank String> lockPointIds) {
}
