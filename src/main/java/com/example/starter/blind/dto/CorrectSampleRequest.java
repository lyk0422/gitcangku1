package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 样本更正请求体：追加一条替代记录并引用最近有效样本，原记录置为被替代。
 *
 * @param phase       被更正样本的阶段：PRE / POST
 * @param sampleCode  新样本编号，全实验唯一，不得复用旧编号
 * @param digest      更正后的合成样本摘要
 * @param collectedAt 更正后的采集时刻，Unix 毫秒，UTC
 */
public record CorrectSampleRequest(
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