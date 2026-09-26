package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 随机表封存请求体。sealKey 仅作为封存承诺令牌参与幂等指纹，不持久化、不出现在任何响应。
 *
 * @param sealKey     封存密钥，非空，最长 64 字符；提交后不可撤销
 * @param tableDigest 调用方持有的随机表摘要（64 位 SHA-256 hex），与库内当前版本不一致时 422
 */
public record SealRandomTableRequest(
        @NotBlank(message = "sealKey 不能为空")
        @Size(max = 64, message = "sealKey 长度不能超过 64 个字符")
        String sealKey,

        @NotBlank(message = "tableDigest 不能为空")
        @Pattern(regexp = "[0-9a-f]{64}", message = "tableDigest 必须为 64 位小写 SHA-256 hex")
        String tableDigest
) {
}
