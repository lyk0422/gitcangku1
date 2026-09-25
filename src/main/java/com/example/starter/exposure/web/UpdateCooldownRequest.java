package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 修改公告冷却配置请求。必须携带公告当前版本号做乐观锁校验；
 * 只影响修改成功后的新申请，历史冷却判定与衰减记录不改写。
 *
 * @param requestId       写操作全局唯一幂等键
 * @param expectedVersion 期望的公告当前版本号；与库中不一致返回 409
 * @param cooldownMinutes 新的最短冷却分钟数，取值 0～1440，0 表示不限制
 */
public record UpdateCooldownRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(0) Long expectedVersion,
        @NotNull @Min(0) @Max(1440) Integer cooldownMinutes
) {
}
