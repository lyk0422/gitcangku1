package com.example.starter.race.persistence;

import com.example.starter.race.domain.RaceStatus;

/**
 * race 表行记录。
 *
 * @param raceId          赛事ID，全局唯一
 * @param version         版本号，从1开始
 * @param status          赛事状态
 * @param relayEnabled    是否接力模式；接力配置写入后为 true 且不可修改
 * @param legCount        接力棒次数（2~8）；非接力赛为 null
 * @param handoffLimitMs  交接区用时上限（毫秒，1~10000）；非接力赛为 null
 * @param createdAt       创建时间，Unix毫秒时间戳
 */
public record RaceRow(
        String raceId,
        int version,
        RaceStatus status,
        boolean relayEnabled,
        Integer legCount,
        Integer handoffLimitMs,
        long createdAt) {
}
