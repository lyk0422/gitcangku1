package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 区组扩容请求体：显式引用已封存版本，新建后继随机表版本。
 *
 * @param baseVersion         显式引用的已封存版本号，必须等于该区组当前最新版本
 * @param addedSeats          新增席位数，2~8 且必须为偶数（保持处理代码各半）
 * @param expectedUnallocated 调用方声明的当前未分配名额；与实际不一致时整次 422
 * @param sealKey             后继版本的封存密钥，必填，最长 128 字符；不在任何响应中返回
 */
public record ExpandBlockRequest(
        @NotNull(message = "baseVersion 不能为空")
        @Min(value = 1, message = "baseVersion 必须不小于 1")
        Integer baseVersion,

        @NotNull(message = "addedSeats 不能为空")
        @Min(value = 2, message = "addedSeats 必须在 2~8 之间且为偶数")
        @Max(value = 8, message = "addedSeats 必须在 2~8 之间且为偶数")
        Integer addedSeats,

        @NotNull(message = "expectedUnallocated 不能为空")
        @Min(value = 0, message = "expectedUnallocated 不能为负数")
        Integer expectedUnallocated,

        @NotBlank(message = "sealKey 不能为空")
        @Size(max = 128, message = "sealKey 长度不能超过 128 个字符")
        String sealKey
) {
}
