package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 样本登记/更正请求。同一参与者同一阶段再次登记即构成更正，
 * 服务端追加替代记录并引用最近有效样本。
 *
 * @param sampleNo    样本编号，全实验唯一，不得跨参与者或跨阶段复用
 * @param phase       阶段：PRE=干预前；POST=干预后
 * @param digest      样本摘要（只保存摘要，不保存真实样本）
 * @param collectedAt 采集时刻，Unix 毫秒，UTC
 */
public record SampleRegisterRequest(
        @NotBlank(message = "sampleNo 不能为空")
        @Size(max = 64, message = "sampleNo 长度不能超过 64 个字符")
        String sampleNo,

        @NotBlank(message = "phase 不能为空")
        @Pattern(regexp = "PRE|POST", message = "phase 仅允许 PRE 或 POST")
        String phase,

        @NotBlank(message = "digest 不能为空")
        @Size(max = 128, message = "digest 长度不能超过 128 个字符")
        String digest,

        @NotNull(message = "collectedAt 不能为空")
        @Positive(message = "collectedAt 必须为正的 Unix 毫秒时刻")
        Long collectedAt
) {
}