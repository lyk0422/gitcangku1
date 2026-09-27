package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          参赛号，赛事内唯一
 * @param finishTimeMs 原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param categoryCode 当前所属组别代码；null 表示未分组
 * @param withdrawn    是否已退赛；退赛者不占组别容量、不参与组别排名
 * @param withdrawnAt  退赛时间，Unix毫秒时间戳；未退赛为 null
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param updatedAt    最近一次计时修订时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        String categoryCode,
        boolean withdrawn,
        Long withdrawnAt,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}
