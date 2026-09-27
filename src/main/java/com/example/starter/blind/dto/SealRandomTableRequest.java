package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 随机表封存请求体。
 *
 * @param sealKey        封存密钥，必填，最长 128 字符；仅落库参与摘要计算，不在任何响应中返回
 * @param expectedDigest 调用方声明的随机表摘要（可选）；提供时与服务端计算摘要不一致返回 422
 */
public record SealRandomTableRequest(
        @NotBlank(message = "sealKey 不能为空")
        @Size(max = 128, message = "sealKey 长度不能超过 128 个字符")
        String sealKey,

        @Size(max = 64, message = "expectedDigest 长度不能超过 64 个字符")
        String expectedDigest
) {
}
