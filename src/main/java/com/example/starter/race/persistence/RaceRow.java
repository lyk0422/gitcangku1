package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RaceType;

/**
 * race 表行记录。
 *
 * @param raceId    赛事ID，全局唯一
 * @param version   版本号，从1开始
 * @param status    赛事状态
 * @param raceType  赛事类型：STANDARD-标准赛事，LOOP-绕圈赛事
 * @param totalLaps 绕圈赛事总圈数（2~10）；标准赛事为 null
 * @param createdAt 创建时间，Unix毫秒时间戳
 */
public record RaceRow(
        String raceId,
        int version,
        RaceStatus status,
        RaceType raceType,
        Integer totalLaps,
        long createdAt) {
}