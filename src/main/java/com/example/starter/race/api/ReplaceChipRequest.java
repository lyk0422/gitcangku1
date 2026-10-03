package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 芯片更换请求：登记旧新芯片、UTC生效时刻与裁判确认。
 *
 * @param replacementId   全局唯一更换记录ID（第二层幂等键）
 * @param oldChipCode     旧芯片代码，必须与选手当前生效芯片一致
 * @param newChipCode     新芯片代码，不得与旧芯片相同且不得已被其他未完赛选手绑定
 * @param effectiveAt     更换生效时刻（Unix毫秒时间戳，UTC）；生效前读数归旧芯片，生效时刻起归新芯片（左闭右开）
 * @param referee         确认更换的裁判
 * @param operator        提交更换的操作者（计入幂等指纹）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ReplaceChipRequest(
        @NotBlank String replacementId,
        @NotBlank String oldChipCode,
        @NotBlank String newChipCode,
        @NotNull Long effectiveAt,
        @NotBlank String referee,
        @NotBlank String operator,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}