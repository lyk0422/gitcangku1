package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;

/**
 * race 表行记录。
 *
 * @param raceId      赛事ID，全局唯一
 * @param version     版本号，从1开始
 * @param status      赛事状态
 * @param baseStartMs 赛事基准（枪声）起跑时刻（Unix 毫秒时间戳，UTC）；null 表示创建时未设置
 * @param createdAt   创建时间，Unix毫秒时间戳
 */
public record RaceRow(String raceId, int version, RaceStatus status, Long baseStartMs, long createdAt) {
}
