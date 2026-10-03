package com.example.starter.race.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 选手退赛请求；退赛者不参与排名且不占组别容量，其成绩与分段数据保留。
 *
 * @param bib             参赛号
 * @param expectedVersion 客户端所见赛事版本
 * @param requestId       全局唯一请求ID（幂等键）
 */
public record WithdrawRunnerRequest(
        @NotBlank String bib,
        @NotNull Integer expectedVersion,
        @NotBlank String requestId
) {
}