package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 芯片更换请求：登记旧新芯片、UTC生效时刻与确认裁判。
 *
 * @param replacementId   全局唯一更换记录ID
 * @param oldChip         旧芯片ID；首次更换时声明该选手的初始芯片
 * @param newChip         新芯片ID；不得与赛事内其他在赛选手当前芯片相同
 * @param effectiveAt     新芯片生效时刻，Unix毫秒时间戳（UTC）；不得早于该选手已接收分段读数的提交时刻
 * @param referee         确认本次更换的裁判
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record ReplaceChipRequest(
        @NotBlank String replacementId,
        @NotBlank String oldChip,
        @NotBlank String newChip,
        @NotNull Long effectiveAt,
        @NotBlank String referee,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
