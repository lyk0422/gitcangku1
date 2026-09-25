package com.example.starter.race.api;

import com.example.starter.race.domain.WithdrawalStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 退赛登记响应。
 *
 * @param withdrawalKey       全局唯一退赛登记键
 * @param bib                 退赛选手参赛号
 * @param status              退赛状态：DNS / DNF
 * @param reason              退赛原因
 * @param lastCheckpointCode  DNF 最后通过的检查点代码；DNS 为 null
 * @param revoked             是否已撤销
 * @param createdAt           登记时间，Unix毫秒时间戳
 * @param revokedAt           撤销时间，Unix毫秒时间戳；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawalResponse(
        String withdrawalKey,
        String bib,
        WithdrawalStatus status,
        String reason,
        String lastCheckpointCode,
        boolean revoked,
        long createdAt,
        Long revokedAt
) {
}
