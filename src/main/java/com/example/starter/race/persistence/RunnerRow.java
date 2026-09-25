package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.RunnerStatus;

/**
 * runner 表行记录。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          参赛号，赛事内唯一
 * @param finishTimeMs 原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param entryStatus  参赛状态：ACTIVE-有效，WITHDRAWN-已退赛不参与排名
 * @param createdAt    登记时间，Unix毫秒时间戳
 * @param updatedAt    最近一次计时修订/退赛时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        RunnerStatus entryStatus,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}
