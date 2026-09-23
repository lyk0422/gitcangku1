package com.example.starter.firmware.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 迁移单设备项：提交设备当前队列、分配版本与目标队列。
 */
public record MigrationItemRequest(
        @NotBlank @Size(max = 64) String deviceId,
        @NotNull Long currentCohortId,
        @Min(1) int assignmentVersion,
        @NotNull Long targetCohortId) {
}
