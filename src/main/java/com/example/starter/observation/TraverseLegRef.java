package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 闭合导线的一条边引用：指定观测记录及其期望当前版本；版本非当前时整次生成 409。
 *
 * @param observationId 观测记录唯一标识
 * @param version       期望的观测当前版本号
 */
public record TraverseLegRef(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer version) {
}
