package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 检验台注册请求。
 *
 * @param commandKey 幂等命令键
 * @param benchKey   检验台业务键，全局唯一
 * @param name       检验台名称
 * @param capacity   台位容量：同一左闭右开时段内最多并行的检验数量，正整数
 */
public record BenchRegisterRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String benchKey,
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(1) Integer capacity) {
}
