package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 迁移决定请求（切换/取消共用）。仅当前保管人可执行。
 *
 * @param commandKey      幂等命令键
 * @param expectedVersion 期望的迁移版本号，须与当前版本一致
 */
public record MigrationDecideRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotNull Long expectedVersion) {
}
