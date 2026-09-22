package com.example.starter.race.persistence;

import com.example.starter.race.domain.PenaltyType;

/**
 * penalty 表行记录。
 *
 * @param penaltyId 处罚ID，全局唯一
 * @param raceId    所属赛事ID
 * @param bib       被罚选手参赛号
 * @param type      处罚类型
 * @param amountMs  加时毫秒数（1~3600000）；取消资格为 null
 * @param revoked   是否已撤销
 * @param createdAt 新增时间，Unix毫秒时间戳
 * @param revokedAt 撤销时间，Unix毫秒时间戳；未撤销为 null
 */
public record PenaltyRow(
        String penaltyId,
        String raceId,
        String bib,
        PenaltyType type,
        Long amountMs,
        boolean revoked,
        long createdAt,
        Long revokedAt
) implements com.example.starter.race.domain.ResultCalculator.PenaltyView {
}
