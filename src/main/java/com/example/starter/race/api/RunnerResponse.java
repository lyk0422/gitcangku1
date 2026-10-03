package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手信息响应。
 *
 * @param bib          参赛号
 * @param categoryCode 所属组别代码；未分配组别为 null
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param withdrawn    是否已退赛
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param updatedAt    最近计时修订时间，Unix毫秒时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerResponse(
        String bib,
        String categoryCode,
        Long finishTimeMs,
        boolean withdrawn,
        long createdAt,
        long updatedAt
) {
}