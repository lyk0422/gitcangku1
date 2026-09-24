package com.example.starter.race.persistence;

/**
 * race_group_member 表行记录（分组成员；同一赛事内一名选手最多属于一个分组）。
 *
 * @param raceId    所属赛事ID
 * @param groupCode 所属分组代码
 * @param bib       选手参赛号
 * @param createdAt 划入分组时间，Unix毫秒时间戳
 */
public record GroupMemberRow(
        String raceId,
        String groupCode,
        String bib,
        long createdAt
) {
}
