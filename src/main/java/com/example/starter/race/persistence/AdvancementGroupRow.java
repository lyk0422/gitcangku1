package com.example.starter.race.persistence;

/**
 * advancement_group 表行记录（一次性划分、不可改写的分组定义）。
 *
 * @param raceId    所属赛事ID
 * @param groupCode 分组代码，赛事内唯一
 * @param position  分组顺序，从1连续递增
 * @param version   划分成功后的赛事版本号
 * @param createdAt 划分时间，Unix毫秒时间戳
 */
public record AdvancementGroupRow(
        String raceId,
        String groupCode,
        int position,
        int version,
        long createdAt
) {
}
