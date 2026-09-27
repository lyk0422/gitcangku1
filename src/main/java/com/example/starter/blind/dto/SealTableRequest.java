package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 封存请求体：研究侧按约定算法提交随机表摘要；
 * 服务端用 sealKey 与库内版本信息复核摘要，摘要内容不匹配返回 422。
 *
 * @param sequenceDigest 随机表摘要，64 位十六进制 SHA-256，非空
 */
public record SealTableRequest(
        @NotBlank(message = "sequenceDigest 不能为空")
        @Pattern(regexp = "[0-9a-fA-F]{64}", message = "sequenceDigest 必须为 64 位十六进制 SHA-256")
        String sequenceDigest
) {
}
