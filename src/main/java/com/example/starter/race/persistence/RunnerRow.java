package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录。
 *
 * @param id              自增主键
 * @param raceId          所属赛事ID
 * @param bib             参赛号，赛事内唯一
 * @param finishTimeMs    原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param timingVersion   原始计时版本号，从1开始，每次计时修订加一
 * @param segmentVersion  分段判定版本号，从1开始，该选手每新增一条分段记录加一
 * @param createdAt       登记时间，Unix毫秒时间戳
 * @param updatedAt       最近一次计时修订（finishAt）时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        int timingVersion,
        int segmentVersion,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}
