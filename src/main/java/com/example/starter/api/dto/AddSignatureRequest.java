package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 为制品版本追加签名请求。
 *
 * @param keyId  签名钥匙标识（须已登记且未撤销）
 * @param digest 签名声明的内容摘要，SHA-256 十六进制小写，必须等于制品内容摘要
 */
public record AddSignatureRequest(
        @NotBlank @Size(max = 128) String keyId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}", message = "digest 必须为 64 位十六进制小写 SHA-256")
        String digest) {
}
