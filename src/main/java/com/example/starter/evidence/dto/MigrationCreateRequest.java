package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 介质迁移创建请求。仅当前保管人可为 SEALED 证物创建；创建时固定源介质版本与新介质号，
 * 新介质收到合成内容后仅为 UNVERIFIED。
 *
 * @param commandKey    幂等命令键
 * @param migrationKey  迁移业务键，全局唯一
 * @param targetMediaNo 新介质编号，同一证物内唯一
 * @param targetSha256  新介质收到的合成内容 SHA-256 摘要（64 位小写十六进制），验证前不可信
 */
public record MigrationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String migrationKey,
        @NotBlank @Size(max = 64) String targetMediaNo,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "targetSha256 须为 64 位小写十六进制")
        String targetSha256) {
}
