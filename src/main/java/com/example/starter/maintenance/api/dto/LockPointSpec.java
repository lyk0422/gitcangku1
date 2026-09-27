package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 锁定点申报项：创建隔离令时随令固化，后续不可增删。
 *
 * @param lockPointId  锁定点标识，同一隔离令内唯一，重复返回 422
 * @param description   锁定点描述（能源类型、物理位置等）
 */
public record LockPointSpec(
        @NotBlank @Size(max = 64) String lockPointId,
        @NotBlank @Size(max = 256) String description) {
}
