package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建介质迁移请求。仅当前保管人可为正常 SEALED 且无借出、无待交接的证物创建；
 * 固定当前 ACTIVE 源介质版本与新介质号，新介质收到合成内容后仅为 UNVERIFIED。
 *
 * @param commandKey    幂等命令键
 * @param targetMediaNo 新介质编号，证物内唯一
 * @param targetSha256  新介质收到的合成内容 SHA-256（小写十六进制，64 字符）
 */
public record MigrationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String targetMediaNo,
        @NotBlank @Size(min = 64, max = 64) String targetSha256) {
}
