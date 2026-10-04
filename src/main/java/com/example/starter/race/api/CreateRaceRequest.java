package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 新建赛事请求。
 *
 * @param raceId    赛事ID，全局唯一
 * @param requestId 全局唯一请求ID（幂等键）
 * @param raceType  赛事类型：STANDARD-标准（默认），LAP-绕圈；绕圈赛事才启用圈次规则
 * @param totalLaps 绕圈赛事总圈数（2~10），raceType=LAP 时必填；标准赛事不得携带
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        @NotBlank String requestId,
        String raceType,
        @Min(2) @Max(10) Integer totalLaps
) {

    /** 兼容旧调用的构造器：标准赛事。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, requestId, null, null);
    }
}