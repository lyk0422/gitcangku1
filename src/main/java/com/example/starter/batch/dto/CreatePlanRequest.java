package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建抽样检验计划请求。sampleSize 样本量 1～200；acceptNumber 为 Ac，rejectNumber 为 Re，
 * 须满足 0≤Ac&lt;Re≤样本量；basis 为非空抽样依据。
 */
public record CreatePlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "planKey 不能为空") String planKey,
        @NotNull(message = "sampleSize 不能为空")
        @Min(value = 1, message = "样本量最小为 1")
        @Max(value = 200, message = "样本量最大为 200")
        Integer sampleSize,
        @NotNull(message = "acceptNumber 不能为空")
        @Min(value = 0, message = "接收数 Ac 最小为 0")
        Integer acceptNumber,
        @NotNull(message = "rejectNumber 不能为空")
        @Min(value = 1, message = "拒收数 Re 最小为 1")
        Integer rejectNumber,
        @NotBlank(message = "抽样依据不能为空") String basis
) {
}
