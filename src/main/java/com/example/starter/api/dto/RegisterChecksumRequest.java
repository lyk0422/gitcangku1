package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 摘要登记请求：为制品版本登记内容校验摘要。
 *
 * <p>摘要格式为 {@code sha256:} 前缀加 64 位十六进制字符（大小写不敏感，
 * 存储与比较统一规范化为小写）。
 */
public record RegisterChecksumRequest(@NotBlank String checksum) {
}
