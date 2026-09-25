package com.example.starter.race.persistence;

/**
 * evidence_withdrawal 表行记录（未裁决证据撤回留痕）。
 *
 * @param evidenceId 被撤回的证据ID，全局唯一
 * @param raceId     所属赛事ID
 * @param operator   撤回操作者标识
 * @param createdAt  撤回时间，Unix毫秒时间戳
 */
public record EvidenceWithdrawalRow(
        String evidenceId,
        String raceId,
        String operator,
        long createdAt
) {
}
