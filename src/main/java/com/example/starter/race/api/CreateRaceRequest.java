package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 新建赛事请求。
 *
 * @param raceId      赛事ID，全局唯一
 * @param baseStartAt 赛事基准起跑时刻，Unix毫秒UTC时间戳；波次净计时相对该时刻计算。
 *                    可空，省略时取服务端当前UTC时刻
 * @param requestId   全局唯一请求ID（幂等键）
 */
public record CreateRaceRequest(
        @NotBlank String raceId,
        @Positive Long baseStartAt,
        @NotBlank String requestId
) {

    /** 未显式指定基准起跑时刻的兼容构造器：由服务端在创建时写入当前UTC时刻。 */
    public CreateRaceRequest(String raceId, String requestId) {
        this(raceId, null, requestId);
    }
}
