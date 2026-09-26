package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

/**
 * runner 表行记录。
 *
 * @param id           自增主键
 * @param raceId       所属赛事ID
 * @param bib          参赛号，赛事内唯一
 * @param finishTimeMs 原始完赛耗时（毫秒，1~86400000）；null 表示计时缺失
 * @param withdrawn      是否已退赛：true 表示已退赛（状态 WITHDRAWN，不排名）
 * @param withdrawnAt    退赛登记时间，Unix毫秒时间戳；未退赛为 null
 * @param withdrawReason 退赛原因，登记时固化；未退赛为 null
 * @param createdAt      登记时间，Unix毫秒时间戳
 * @param updatedAt      最近一次计时修订时间，Unix毫秒时间戳
 */
public record RunnerRow(
        long id,
        String raceId,
        String bib,
        Long finishTimeMs,
        boolean withdrawn,
        Long withdrawnAt,
        String withdrawReason,
        long createdAt,
        long updatedAt
) implements ResultCalculator.RunnerView {
}
