package com.example.starter.race.persistence;

/**
 * race_group 表行记录（赛事分组，一次性划分后不可修改）。
 *
 * @param raceId    所属赛事ID
 * @param groupCode 分组代码，赛事内唯一
 * @param createdAt 划分时间，Unix毫秒时间戳
 */
public record GroupRow(
        String raceId,
        String groupCode,
        long createdAt
) {
}
