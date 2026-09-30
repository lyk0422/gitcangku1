package com.example.starter.race.persistence;

/**
 * team 表行记录：赛事内的团队主数据，随首次名单锁定自动创建。
 *
 * @param id        自增主键
 * @param raceId    所属赛事ID
 * @param teamId    团队ID，同一赛事内唯一
 * @param createdAt 团队首次锁定名单时间，Unix毫秒时间戳
 */
public record TeamRow(
        long id,
        String raceId,
        String teamId,
        long createdAt
) {
}
