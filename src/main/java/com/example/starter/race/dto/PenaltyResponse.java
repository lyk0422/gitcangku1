package com.example.starter.race.dto;

/**
 * 处罚响应。
 *
 * @param penaltyId 处罚ID
 * @param raceId    赛事ID
 * @param bib       被处罚选手参赛号
 * @param type      处罚类型（TIME_ADD/DISQUALIFY）
 * @param amountMs  加时毫秒数；DISQUALIFY 为 null
 * @param revoked   是否已撤销
 * @param version   操作后的赛事版本
 */
public record PenaltyResponse(String penaltyId, String raceId, String bib, String type,
                              Long amountMs, boolean revoked, long version) {
}
