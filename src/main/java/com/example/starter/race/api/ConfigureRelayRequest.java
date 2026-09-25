package com.example.starter.race.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 将 OPEN 赛事配置为接力赛；配置成功后赛事版本加一，接力配置不可再修改。
 *
 * @param legCount        棒次数（2~8）
 * @param handoffLimitMs  交接区用时上限（毫秒，1~10000），超过即判犯规
 * @param expectedVersion 客户端所见赛事版本，必须与当前版本一致
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ConfigureRelayRequest(
        @NotNull @Min(2) @Max(8) Integer legCount,
        @NotNull @Min(1) @Max(10_000) Integer handoffLimitMs,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
