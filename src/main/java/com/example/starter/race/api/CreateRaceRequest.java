package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 新建赛事请求。
 *
 * @param raceId      赛事ID，全局唯一
 * @param requestId   全局唯一请求ID（幂等键）
 * @param scoringMode 计分模式：STANDARD（默认）/ HANDICAP；须在比赛首次计时前（创建时）选定
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        @NotBlank String requestId,
        @Pattern(regexp = "STANDARD|HANDICAP") String scoringMode
) {

    /** 兼容旧调用：默认 STANDARD 模式。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, requestId, null);
    }
}
