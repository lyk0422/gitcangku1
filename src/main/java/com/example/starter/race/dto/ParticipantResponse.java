package com.example.starter.race.dto;

/**
 * 选手响应。
 *
 * @param raceId     赛事ID
 * @param bib        参赛号
 * @param rawTimeMs  原始完赛耗时（毫秒）；null表示计时缺失
 * @param version    操作后的赛事版本
 */
public record ParticipantResponse(String raceId, String bib, Long rawTimeMs, long version) {
}
