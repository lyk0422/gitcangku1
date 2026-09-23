package com.example.starter.race.persistence;

import com.example.starter.race.domain.PenaltyType;

/**
 * penalty 表行记录。
 *
 * @param penaltyId            处罚ID，全局唯一
 * @param raceId               所属赛事ID
 * @param bib                  被罚选手参赛号
 * @param type                 处罚类型
 * @param amountMs             加时毫秒数（1~3600000，REPLACE 替代罚时允许0）；取消资格为 null
 * @param version              处罚版本号，从1开始；REPLACE 裁决生成新版本
 * @param superseded           是否已被 REPLACE 裁决的新版本取代（历史版本不删除、不参与计算）
 * @param supersedesPenaltyId  新处罚版本所取代的旧处罚ID；非替换产生为 null
 * @param revoked              是否已撤销
 * @param createdAt            新增时间，Unix毫秒时间戳
 * @param revokedAt            撤销时间，Unix毫秒时间戳；未撤销为 null
 */
public record PenaltyRow(
        String penaltyId,
        String raceId,
        String bib,
        PenaltyType type,
        Long amountMs,
        int version,
        boolean superseded,
        String supersedesPenaltyId,
        boolean revoked,
        long createdAt,
        Long revokedAt
) implements com.example.starter.race.domain.ResultCalculator.PenaltyView {

    /** 兼容处罚版本链引入前的构造器：版本1、未被取代、无上级旧处罚。 */
    public PenaltyRow(
            String penaltyId,
            String raceId,
            String bib,
            PenaltyType type,
            Long amountMs,
            boolean revoked,
            long createdAt,
            Long revokedAt) {
        this(penaltyId, raceId, bib, type, amountMs, 1, false, null, revoked, createdAt, revokedAt);
    }
}
