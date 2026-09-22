package com.example.starter.race.repo;

/**
 * 选手行。
 *
 * @param raceId    赛事ID
 * @param bib       参赛号
 * @param rawTimeMs 原始完赛耗时（毫秒）；null表示计时缺失
 */
public record ParticipantRow(String raceId, String bib, Long rawTimeMs) {
}
