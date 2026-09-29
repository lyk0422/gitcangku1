package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 更换计时芯片请求（选手未冲线前可申请，需裁判确认）。
 *
 * @param replacementId 更换记录ID，全局唯一
 * @param oldChipCode   旧芯片代码（必须与选手当前芯片一致）
 * @param newChipCode   新芯片代码（不得当前绑定在其他未完赛选手名下）
 * @param effectiveAtMs UTC 生效时刻（毫秒）；不得早于已接收分段读数时刻，左闭右开边界
 * @param referee       裁判确认人（操作者账号）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId     全局唯一请求ID（幂等键）
 */
public record ReplaceChipRequest(
        @NotBlank String replacementId,
        @NotBlank @Size(max = 128) String oldChipCode,
        @NotBlank @Size(max = 128) String newChipCode,
        @NotNull @Positive Long effectiveAtMs,
        @NotBlank @Size(max = 128) String referee,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
