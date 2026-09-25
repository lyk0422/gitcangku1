package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 配置空域单元 15 分钟桶容量上限请求（新建或调整，幂等）。
 *
 * @param cellId      空域单元标识，格式 gx:gy
 * @param bucketStart 15 分钟 UTC 时间桶起点，epoch 秒，须为 900 的整数倍
 * @param maxFlights  最大航班数（占用上限），>= 0
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record CapacityConfigRequest(
        @NotBlank @Size(max = 64) String cellId,
        @NotNull Long bucketStart,
        @NotNull @Min(0) Integer maxFlights,
        @NotBlank @Size(max = 64) String requestId) {
}
