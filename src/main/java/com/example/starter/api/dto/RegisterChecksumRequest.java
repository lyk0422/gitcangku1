package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 制品版本内容校验摘要登记请求。
 */
public record RegisterChecksumRequest(
        @NotBlank String algorithm,
        @NotBlank String digest) {
}
