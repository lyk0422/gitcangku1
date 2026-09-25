package com.example.starter.race.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 交接提交请求：记录某队某交接棒次接棒选手的累计用时与交接区实际用时。
 *
 * @param teamKey         队伍标识
 * @param leg             交接棒次（接棒选手棒次，大于1且不超过配置棒次数）
 * @param receiver        接棒选手标识，须与登记的该棒次选手一致
 * @param elapsedMillis   接棒选手累计用时（毫秒），须大于上一棒次记录值
 * @param zoneMillis      交接区实际用时（毫秒，非负），超过赛事上限判犯规
 * @param expectedVersion 客户端所见赛事版本，服务端据此做乐观并发控制
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record RelayHandoffRequest(
        @NotBlank String teamKey,
        @NotNull @Min(2) Integer leg,
        @NotBlank String receiver,
        @NotNull @Positive Long elapsedMillis,
        @NotNull @PositiveOrZero Long zoneMillis,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
