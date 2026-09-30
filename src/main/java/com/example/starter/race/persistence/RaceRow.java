package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;

/**
 * race 表行记录。
 *
 * @param raceId 赛事ID，全局唯一
 * @param version 版本号，从1开始
 * @param status 赛事状态
 * @param depositFen 申诉保证金当前配置金额，单位：分；0表示未配置；已创建申诉不受其后续变化影响
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record RaceRow(String raceId, int version, RaceStatus status, long depositFen, long createdAt) {
}
