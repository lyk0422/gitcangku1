package com.example.starter.race.persistence;

import com.example.starter.race.domain.RunnerStatus;
import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          参赛号，赛事内唯一
 * @param finishTimeMs 原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param status       在赛状态：ACTIVE-在赛，WITHDRAWN-已退赛
 * @param categoryCode 当前所属组别；组别重分批准后整体迁移
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param updatedAt    最近一次计时修订时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        RunnerStatus status,
        String categoryCode,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}
