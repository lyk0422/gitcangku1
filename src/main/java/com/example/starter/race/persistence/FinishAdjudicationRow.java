package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;

import java.util.List;

/**
 * finish_adjudication 表行记录：不可变证据裁决快照（只插不改）。
 *
 * @param id             自增主键，同事务组内大者生效
 * @param adjudicationId 裁决ID，全局唯一
 * @param raceId         所属赛事ID
 * @param finishTimeMs   被裁决计时组共享的原始完赛耗时（毫秒）
 * @param evidenceIds    本次裁决覆盖的证据ID列表
 * @param finalOrder     裁决后的名次顺序（候选全排列，名次互不重复）
 * @param operator       裁决操作者
 * @param raceVersion    裁决完成后的赛事版本号
 * @param createdAt      裁决时间，Unix毫秒时间戳
 */
public record FinishAdjudicationRow(
        long id,
        String adjudicationId,
        String raceId,
        long finishTimeMs,
        List<String> evidenceIds,
        List<String> finalOrder,
        String operator,
        int raceVersion,
        long createdAt
) implements ResultCalculator.AdjudicationView {
}
