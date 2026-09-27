package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建隔离令时提交的单个锁定点。
 *
 * @param lockPointId  锁定点标识，同一隔离令内不得重复（重复返回 422）
 * @param name         锁定点名称/位置描述，可为 null
 */
public record LockPointInput(
        @NotBlank String lockPointId,
        String name) {
}
