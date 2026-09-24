package com.example.starter.batch.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建抽样检验计划请求。
 * 样本量 1～200；接收数 Ac 与拒收数 Re 满足 0≤Ac&lt;Re≤样本量（跨字段校验在服务层完成）。
 */
public record CreateSamplingPlanRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotBlank(message = "planKey 不能为空") String planKey,
        @NotNull(message = "sampleSize 不能为空")
        @Min(value = 1, message = "样本量必须在 1～200 之间")
        @Max(value = 200, message = "样本量必须在 1～200 之间")
        Integer sampleSize,
        @NotNull(message = "acceptNumber 不能为空")
        @Min(value = 0, message = "接收数 Ac 不能为负")
        Integer acceptNumber,
        @NotNull(message = "rejectNumber 不能为空")
        @Min(value = 0, message = "拒收数 Re 不能为负")
        Integer rejectNumber,
        @NotBlank(message = "抽样依据 basis 不能为空") String basis
) {
}
