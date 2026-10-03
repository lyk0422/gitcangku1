package com.example.starter.race.persistence;

/**
 * chip_replacement 表行记录：一次芯片更换的固化档案。
 *
 * @param replacementId   更换记录ID，全局唯一（第二层幂等键）
 * @param raceId          所属赛事ID
 * @param bib             选手参赛号
 * @param oldChipCode     旧芯片代码
 * @param newChipCode     新芯片代码
 * @param effectiveAt     更换生效时刻（Unix毫秒时间戳，UTC）
 * @param referee         确认更换的裁判
 * @param operator        提交更换的操作者
 * @param reassignedCount 重算后归属发生变化的分段读数数量
 * @param createdAt       更换登记时间，Unix毫秒时间戳
 */
public record ChipReplacementRow(
        String replacementId,
        String raceId,
        String bib,
        String oldChipCode,
        String newChipCode,
        long effectiveAt,
        String referee,
        String operator,
        int reassignedCount,
        int raceVersion,
        long createdAt
) {
}