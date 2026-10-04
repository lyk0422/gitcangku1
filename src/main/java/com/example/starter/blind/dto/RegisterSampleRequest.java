package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登记合成样本请求体；只提交摘要与采集 UTC 时刻，不提交真实样本。
 *
 * @param phase       样本阶段：PRE / POST
 * @param sampleCode  样本编号，全实验唯一，不得跨参与者或跨阶段复用
 * @param digest      合成样本摘要
 * @param collectedAt 采集时刻，Unix 毫秒，UTC
 */
public record RegisterSampleRequest(
        @NotBlank(message = "phase 不能为空")
        @Pattern(regexp = "PRE|POST", message = "phase 仅允许 PRE 或 POST")
        String phase,
        @NotBlank(message = "sampleCode 不能为空")
        @Size(max = 64, message = "sampleCode 长度不能超过 64 个字符")
        String sampleCode,
        @NotBlank(message = "digest 不能为空")
        @Size(max = 128, message = "digest 长度不能超过 128 个字符")
        String digest,
        @NotNull(message = "collectedAt 不能为空")
        Long collectedAt
) {
}