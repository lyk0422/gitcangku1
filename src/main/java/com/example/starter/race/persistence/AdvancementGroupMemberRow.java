package com.example.starter.race.persistence;

/**
 * advancement_group_member 表行记录（选手与分组的唯一归属）。
 *
 * @param raceId    所属赛事ID
 * @param groupCode 所属分组代码
 * @param bib       选手参赛号
 */
public record AdvancementGroupMemberRow(
        String raceId,
        String groupCode,
        String bib
) {
}
