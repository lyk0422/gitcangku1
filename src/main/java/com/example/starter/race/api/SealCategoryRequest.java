package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 组别封榜请求；校验版本后原子固化该组别当前在赛选手的只读成绩快照。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SealCategoryRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}