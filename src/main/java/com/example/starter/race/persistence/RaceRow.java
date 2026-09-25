package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;

/**
 * race 表行记录。
 *
 * @param raceId    赛事ID，全局唯一
 * @param courseKey 所属赛道标识
 * @param version   版本号，从1开始
 * @param status    赛事状态
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record RaceRow(String raceId, String courseKey, int version, RaceStatus status, long createdAt) {
}
