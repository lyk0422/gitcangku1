package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建实验请求体。
 *
 * @param blockCount 区组数量，2～8，创建时固定；每组固定 4 个席位
 */
public record CreateExperimentRequest(
        @NotNull(message = "blockCount 不能为空")
        @Min(value = 2, message = "区组数量必须在 2~8 之间")
        @Max(value = 8, message = "区组数量必须在 2~8 之间")
        Integer blockCount
) {
}
