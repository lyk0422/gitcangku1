package com.example.starter.race.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 单条退赛登记（撤销后仍保留的不可变历史）。
 *
 * @param withdrawalKey          全局唯一退赛键
 * @param bib                    退赛选手参赛号
 * @param status                 退赛状态：DNS / DNF
 * @param reason                 非空退赛原因
 * @param lastCheckpointCode     DNF 最后通过检查点代码；DNS 为 null
 * @param lastCheckpointPosition 登记时固化的最后通过检查点顺序；DNS 为 null
 * @param revoked                是否已撤销：false-生效中，true-已撤销
 * @param createdAt              登记时间，Unix毫秒时间戳
 * @param revokedAt              撤销时间，Unix毫秒时间戳；未撤销为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WithdrawalResponse(
        String withdrawalKey,
        String bib,
        String status,
        String reason,
        String lastCheckpointCode,
        Integer lastCheckpointPosition,
        boolean revoked,
        long createdAt,
        Long revokedAt
) {
}
