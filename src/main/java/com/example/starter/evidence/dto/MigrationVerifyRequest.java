package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 迁移见证验证请求。仅非保管人见证者可提交；提交的新介质完整摘要必须等于源摘要。
 *
 * @param commandKey     幂等命令键
 * @param digest         见证者计算的新介质完整 SHA-256 摘要（64 位小写十六进制）
 * @param expectedVersion 期望的迁移版本号，须与当前版本一致
 */
public record MigrationVerifyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "digest 须为 64 位小写十六进制")
        String digest,
        @NotNull Long expectedVersion) {
}
