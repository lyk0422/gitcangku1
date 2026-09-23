package com.example.starter.race.persistence;

/**
 * team 表行记录（团队配置头表）。
 *
 * @param teamId    团队代码，赛事内唯一
 * @param raceId    所属赛事ID
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record TeamRow(String teamId, String raceId, long createdAt) {
}
