package com.example.starter.race.persistence;

/**
 * wave 表行记录（分批起跑波次）。
 *
 * @param raceId    所属赛事ID
 * @param waveKey   波次唯一键，同一赛事内唯一
 * @param startMs   该波次 UTC 起跑时刻，Unix 毫秒时间戳；不同波次可相同
 * @param createdAt 波次登记时间，Unix 毫秒时间戳
 * @param updatedAt 波次最近修改时间，Unix 毫秒时间戳
 */
public record WaveRow(
        String raceId,
        String waveKey,
        long startMs,
        long createdAt,
        long updatedAt
) {
}
