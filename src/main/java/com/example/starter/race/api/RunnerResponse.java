package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 选手信息响应。
 *
 * @param bib          参赛号
 * @param finishTimeMs 原始完赛耗时毫秒；计时缺失为 null
 * @param createdAt    登记时间，Unix毫秒时间戳（UTC）
 * @param updatedAt    最近计时修订时间，Unix毫秒时间戳（UTC）
 * @param withdrawn    是否已退赛
 * @param withdrawnAt  退赛时间，Unix毫秒时间戳（UTC）；未退赛为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunnerResponse(
        String bib,
        Long finishTimeMs,
        long createdAt,
        long updatedAt,
        boolean withdrawn,
        Long withdrawnAt
) {

    /** 不含退赛信息的兼容构造器（在赛选手）。 */
    public RunnerResponse(String bib, Long finishTimeMs, long createdAt, long updatedAt) {
        this(bib, finishTimeMs, createdAt, updatedAt, false, null);
    }
}
