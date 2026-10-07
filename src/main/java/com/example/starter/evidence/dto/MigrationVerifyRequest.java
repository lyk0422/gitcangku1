package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 迁移验证请求。由非保管人见证者提交新介质完整摘要；
 * 摘要必须等于源介质摘要才通过（VERIFIED），不一致返回 422，可另键重新提交。
 *
 * @param commandKey 幂等命令键
 * @param sha256     见证者计算的新介质完整 SHA-256（小写十六进制，64 字符）
 */
public record MigrationVerifyRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(min = 64, max = 64) String sha256) {
}
