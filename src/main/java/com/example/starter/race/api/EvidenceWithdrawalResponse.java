package com.example.starter.race.api;

/**
 * 证据撤回记录响应（撤回留痕，不可删除）。
 *
 * @param evidenceId 被撤回的证据ID
 * @param raceId     所属赛事ID
 * @param operator   撤回操作者标识
 * @param createdAt  撤回时间，Unix毫秒时间戳
 */
public record EvidenceWithdrawalResponse(
        String evidenceId,
        String raceId,
        String operator,
        long createdAt
) {
}
