package com.example.starter.race.api;

/**
 * 芯片更换响应：固化旧新芯片、生效时刻、裁判与重算结果。
 *
 * @param replacementId   更换记录ID
 * @param raceId          赛事ID
 * @param bib             参赛号
 * @param oldChipCode     旧芯片代码
 * @param newChipCode     新芯片代码
 * @param effectiveAt     更换生效时刻（Unix毫秒时间戳，UTC）
 * @param referee         确认裁判
 * @param operator        操作者
 * @param reassignedCount 重算后归属发生变化的分段读数数量
 * @param version         更换后的赛事版本号
 * @param createdAt       更换登记时间，Unix毫秒时间戳
 */
public record ChipReplacementResponse(
        String replacementId,
        String raceId,
        String bib,
        String oldChipCode,
        String newChipCode,
        long effectiveAt,
        String referee,
        String operator,
        int reassignedCount,
        int version,
        long createdAt
) {
}