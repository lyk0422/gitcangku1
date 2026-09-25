package com.example.starter.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 配置/调整时空桶容量请求。同一 (cellId, bucketStart) 重复配置视为容量调整。
 *
 * @param cellId      空域单元标识，格式 C<gx>_<gy>
 * @param bucketStart 15 分钟 UTC 时间桶起始，epoch 毫秒（UTC），需对齐 900000 毫秒
 * @param maxFlights  最大航班占用数，>= 0
 * @param requestId   写操作全局唯一请求标识，用于幂等重放
 */
public record CapacityConfigRequest(
        @NotBlank @Size(max = 32) String cellId,
        @NotNull Long bucketStart,
        @NotNull @Min(0) Integer maxFlights,
        @NotBlank @Size(max = 64) String requestId) {
}
