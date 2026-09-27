package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 组别封榜请求；仅固化封榜时刻归属该组别的在赛选手成绩，历史快照不追溯重写。
 *
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record SealCategoryRequest(
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}
