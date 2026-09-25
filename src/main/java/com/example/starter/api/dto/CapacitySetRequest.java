package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设置（或覆盖）格网单元容量请求；该容量对单元上的全部时间桶生效，缺省为 1。
 *
 * @param cellX     格网单元 X 下标
 * @param cellY     格网单元 Y 下标
 * @param capacity  容量，至少 1
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record CapacitySetRequest(
        @NotNull Integer cellX,
        @NotNull Integer cellY,
        @NotNull @Min(1) Integer capacity,
        @NotBlank @Size(max = 64) String requestId) {
}
