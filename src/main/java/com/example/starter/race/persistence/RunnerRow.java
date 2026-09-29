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
 * @param status       参赛状态：ACTIVE-在赛，WITHDRAWN-已退赛
 * @param chipCode     当前生效计时芯片代码；null 表示尚未发放芯片
 * @param createdAt    登记时间，Unix毫秒时间戳（UTC）
 * @param updatedAt    最近一次计时修订/芯片变更时间，Unix毫秒时间戳（UTC）
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        RunnerStatus status,
        String chipCode,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {

    @Override
    public boolean withdrawn() {
        return status == RunnerStatus.WITHDRAWN;
    }
}
