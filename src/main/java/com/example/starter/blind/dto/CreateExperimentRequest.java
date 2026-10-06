package com.example.starter.blind.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 创建实验请求体。
 *
 * @param blockCount     区组数量，2～8，创建时固定；每组固定 4 个席位
 * @param mode           实验模式：STANDARD（默认，单阶段）/ CROSSOVER（交叉两阶段），创建时固定
 * @param washoutMinutes 交叉模式必填的洗脱时长，单位分钟，1～120；STANDARD 模式不得指定
 */
public record CreateExperimentRequest(
        @NotNull(message = "blockCount 不能为空")
        @Min(value = 2, message = "区组数量必须在 2~8 之间")
        @Max(value = 8, message = "区组数量必须在 2~8 之间")
        Integer blockCount,
        String mode,
        @Min(value = 1, message = "洗脱时长必须在 1~120 分钟之间")
        @Max(value = 120, message = "洗脱时长必须在 1~120 分钟之间")
        Integer washoutMinutes
) {
}
