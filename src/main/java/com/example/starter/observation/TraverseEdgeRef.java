package com.example.starter.observation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 闭合导线边引用：按输入顺序引用一条观测记录及其期望当前版本。
 *
 * @param observationId 观测记录唯一标识
 * @param version       期望的观测当前版本号；生成时与实际当前版本不一致返回 409
 */
public record TraverseEdgeRef(
        @NotBlank @Size(max = 64) String observationId,
        @NotNull @Min(1) Integer version) {
}
