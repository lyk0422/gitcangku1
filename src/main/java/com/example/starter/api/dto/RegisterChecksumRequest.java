package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 内容校验摘要登记请求。
 *
 * @param checksum 摘要原文，须为 sha256: 加 64 位小写十六进制
 */
public record RegisterChecksumRequest(
        @NotBlank String checksum) {
}
