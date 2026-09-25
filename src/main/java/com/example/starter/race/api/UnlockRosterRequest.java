package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 裁判解锁队伍名单请求；仅封榜前可用，解锁不删除旧锁定快照，重锁生成新版本。
 *
 * @param reason          解锁原因（必填）
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record UnlockRosterRequest(
        @NotBlank String reason,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
