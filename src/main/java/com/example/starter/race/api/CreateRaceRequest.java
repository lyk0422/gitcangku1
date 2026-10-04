package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建赛事请求。
 *
 * @param raceId    赛事ID，全局唯一
 * @param raceType  赛事类型：STANDARD-标准赛事（默认），LOOP-绕圈赛事；可空表示 STANDARD
 * @param totalLaps 绕圈赛事总圈数（2~10），raceType=LOOP 时必填；标准赛事必须为 null
 * @param requestId 全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        String raceType,
        Integer totalLaps,
        @NotBlank String requestId
) {

    /** 标准赛事兼容构造器：不带赛事类型与圈数。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, null, null, requestId);
    }
}