package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 新建赛事请求（支持器材检录配置）。
 *
 * @param raceId             赛事ID，全局唯一
 * @param inspectionRequired 是否强制检录；为 null 时按 false（非强制）处理
 * @param validMinutes       PASS 检录有效分钟数（1~1440）；非强制检录可空，强制检录必填
 * @param requestId          全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        Boolean inspectionRequired,
        @Min(1) @Max(1440) Integer validMinutes,
        @NotBlank String requestId
) {

    /** 兼容旧调用：非强制检录赛事。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, null, null, requestId);
    }
}
