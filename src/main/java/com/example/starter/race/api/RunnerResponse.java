package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手信息响应。
 *
 * @param bib          参赛号
 * @param status       参赛状态：ACTIVE-在赛，WITHDRAWN-已退赛
 * @param chipCode     当前生效计时芯片代码；尚未发放为 null
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param createdAt    登记时间，Unix毫秒时间戳（UTC）
 * @param updatedAt    最近计时/芯片/状态变更时间，Unix毫秒时间戳（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerResponse(
        String bib,
        String status,
        String chipCode,
        Long finishTimeMs,
        long createdAt,
        long updatedAt
) {
}
