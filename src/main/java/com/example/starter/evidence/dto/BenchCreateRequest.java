package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册检验台请求。容量为同一时段（左闭右开区间）可承接的检验预约数上限。
 *
 * @param commandKey 幂等命令键
 * @param benchKey   检验台业务键，全局唯一
 * @param capacity   同时段容量上限（1..100）
 */
public record BenchCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String benchKey,
        @NotNull @Min(1) @Max(100) Integer capacity) {
}
