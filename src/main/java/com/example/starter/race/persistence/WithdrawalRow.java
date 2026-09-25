package com.example.starter.race.persistence;

import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.WithdrawalStatus;

/**
 * withdrawal 表行记录（一次退赛登记；撤销后行保留且不可变）。
 *
 * @param withdrawalKey       退赛登记键，全局唯一（第二层幂等键）
 * @param raceId              所属赛事ID
 * @param bib                 退赛选手参赛号
 * @param status              退赛状态：DNS-未出发，DNF-中途退赛
 * @param reason              退赛原因，非空
 * @param lastCheckpointCode  DNF 登记时最后通过的检查点代码；DNS 为 null
 * @param revoked             是否已撤销：FALSE-生效中，TRUE-已撤销
 * @param createdAt           退赛登记时间，Unix毫秒时间戳
 * @param revokedAt           撤销时间，Unix毫秒时间戳；未撤销为 null
 */
public record WithdrawalRow(
        String withdrawalKey,
        String raceId,
        String bib,
        WithdrawalStatus status,
        String reason,
        String lastCheckpointCode,
        boolean revoked,
        long createdAt,
        Long revokedAt
) implements ResultCalculator.WithdrawalView {
}
