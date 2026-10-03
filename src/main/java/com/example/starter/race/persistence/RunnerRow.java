package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          参赛号，赛事内唯一
 * @param categoryCode 所属组别代码；未分配组别为 null
 * @param finishTimeMs 原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param withdrawn    是否已退赛；退赛者不参与排名且不占组别容量
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param updatedAt    最近一次计时修订时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        String categoryCode,
        Long finishTimeMs,
        boolean withdrawn,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}