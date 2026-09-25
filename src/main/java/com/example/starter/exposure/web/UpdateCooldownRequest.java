package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改公告冷却配置请求。必须携带公告当前版本号，冲突返回 409；只影响后续申请。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param cooldownMinutes 同一访客两次 CONFIRMED 之间的最短冷却分钟数，0～1440，0 表示不限制
 * @param expectedVersion 公告当前配置版本号，与服务器不一致时返回 409
 */
public record UpdateCooldownRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) @Max(1440) Integer cooldownMinutes,
        @NotNull @Min(0) Long expectedVersion
) {
}
