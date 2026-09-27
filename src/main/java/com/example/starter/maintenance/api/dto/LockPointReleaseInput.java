package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 单个锁定点的解除项：逐一解除并写原因。
 *
 * @param pointId  锁定点标识
 * @param reason   该点解除原因（非空，随解除证据固化）
 */
public record LockPointReleaseInput(
        @NotBlank String pointId,
        @NotBlank String reason) {
}
