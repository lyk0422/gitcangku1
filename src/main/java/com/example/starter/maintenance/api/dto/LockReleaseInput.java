package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 解除隔离时对单个锁定点的解除确认：必须逐一覆盖创建时的完整锁定点集合。
 *
 * @param lockPointId 被解除的锁定点标识
 * @param operatorId  执行该锁定点解除确认的操作人
 */
public record LockReleaseInput(
        @NotBlank String lockPointId,
        @NotBlank String operatorId) {
}
