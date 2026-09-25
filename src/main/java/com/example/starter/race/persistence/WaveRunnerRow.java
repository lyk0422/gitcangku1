package com.example.starter.race.persistence;

/**
 * wave_runner 表行记录：参赛者与波次的所属关系；同一参赛者主键上只能属于一个波次。
 *
 * @param raceId    所属赛事ID
 * @param bib       参赛号
 * @param waveKey   所属波次唯一键
 * @param createdAt 加入波次时间，Unix毫秒时间戳
 * @param updatedAt 最近一次波次变更时间，Unix毫秒时间戳
 */
public record WaveRunnerRow(
        String raceId,
        String bib,
        String waveKey,
        long createdAt,
        long updatedAt
) {
}
