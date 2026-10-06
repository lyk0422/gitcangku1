package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建赛事请求。
 *
 * @param raceId      赛事ID，全局唯一
 * @param rankingMode 排名模式：TOTAL_TIME / COUNTBACK；缺省为 TOTAL_TIME
 * @param requestId   全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        String rankingMode,
        @NotBlank String requestId
) {

    /** 兼容旧调用的构造器：默认 TOTAL_TIME 排名模式。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, null, requestId);
    }
}
