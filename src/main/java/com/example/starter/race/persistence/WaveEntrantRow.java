package com.example.starter.race.persistence;

/**
 * wave_entrant 表行记录（参赛者与波次的归属）。
 * 主键为 (race_id, bib)，因此同一参赛者在同一赛事中只能属于一个波次。
 *
 * @param raceId    所属赛事ID
 * @param bib       参赛者参赛号
 * @param waveKey   所属波次唯一键
 * @param createdAt 入波时间，Unix 毫秒时间戳
 */
public record WaveEntrantRow(
        String raceId,
        String bib,
        String waveKey,
        long createdAt
) {
}
