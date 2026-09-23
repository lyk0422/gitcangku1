package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 为制品版本追加签名的请求：keyId 唯一一把钥匙一份，digest 必须等于制品内容摘要。
 */
public record AddSignatureRequest(
        @NotBlank String keyId,
        @NotBlank String digest) {
}
