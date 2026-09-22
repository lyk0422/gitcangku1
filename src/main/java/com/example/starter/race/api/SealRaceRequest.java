package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 封榜请求；校验版本后原子保存全体选手只读成绩快照并转 SEALED。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SealRaceRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
