package com.example.starter.race.persistence;

import com.example.starter.race.domain.PenaltyType;

/**
 * penalty_revision 表行记录：被 REPLACE 裁决替换的处罚旧版本，关联新旧版本。
 *
 * @param penaltyId           所属处罚ID
 * @param version             被替换的旧版本号
 * @param type                旧版本处罚类型
 * @param amountMs            旧版本加时毫秒数；取消资格为 null
 * @param supersededByVersion 替换生成的新版本号
 * @param appealKey           触发替换的申诉键
 * @param supersededAt        替换时间，Unix毫秒时间戳
 */
public record PenaltyRevisionRow(
        String penaltyId,
        int version,
        PenaltyType type,
        Long amountMs,
        int supersededByVersion,
        String appealKey,
        long supersededAt
) {
}
