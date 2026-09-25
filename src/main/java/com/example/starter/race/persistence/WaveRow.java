package com.example.starter.race.persistence;

/**
 * wave 表行记录（分批起跑波次）。
 *
 * @param raceId    所属赛事ID
 * @param waveKey   波次唯一键，同一赛事内唯一
 * @param startAt   波次UTC起跑时刻，Unix毫秒时间戳
 * @param createdAt 登记时间，Unix毫秒时间戳
 * @param updatedAt 最近一次波次变更时间，Unix毫秒时间戳
 */
public record WaveRow(
        String raceId,
        String waveKey,
        long startAt,
        long createdAt,
        long updatedAt
) {
}
