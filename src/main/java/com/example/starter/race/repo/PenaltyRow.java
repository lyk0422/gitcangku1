package com.example.starter.race.repo;

/**
 * 处罚行。
 *
 * @param penaltyId 处罚ID
 * @param raceId    赛事ID
 * @param bib       参赛号
 * @param type      类型（TIME_ADD/DISQUALIFY）
 * @param amountMs  加时毫秒数；DISQUALIFY 为 null
 * @param revoked   是否已撤销
 */
public record PenaltyRow(String penaltyId, String raceId, String bib, String type, Long amountMs,
                         boolean revoked) {
}
