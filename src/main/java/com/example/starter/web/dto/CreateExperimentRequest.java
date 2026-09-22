package com.example.starter.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建实验请求。
 *
 * @param experimentId 实验唯一编号
 * @param blockCount   固定区组数量，范围 2～8
 * @param requestId    全局唯一写操作请求编号
 */
public record CreateExperimentRequest(
        @NotBlank String experimentId,
        @NotNull @Min(2) @Max(8) Integer blockCount,
        @NotBlank String requestId
) {
}
