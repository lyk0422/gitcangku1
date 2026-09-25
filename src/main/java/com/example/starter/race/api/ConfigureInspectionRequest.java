package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 配置赛事器材检录请求（仅 OPEN 赛事可配置，可重复配置；历史 PASS 按提交时快照计算有效期）。
 *
 * @param mandatory       是否强制检录：true 时起跑/首个分段计时前须有未过期 PASS
 * @param validMinutes    检录 PASS 有效分钟数，取值 1~1440
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureInspectionRequest(
        @NotNull Boolean mandatory,
        @NotNull @Min(1) @Max(1440) Integer validMinutes,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
