package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;

/**
 * runner_withdrawal 表行记录（退赛登记历史，撤销后仍不可变保留）。
 *
 * @param id                     自增主键
 * @param withdrawalKey          全局唯一退赛键；撤销须携带登记时同一键
 * @param raceId                 所属赛事ID
 * @param bib                    退赛选手参赛号
 * @param status                 退赛状态：DNS-未出发，DNF-中途退赛
 * @param reason                 非空退赛原因
 * @param lastCheckpointCode     DNF 最后通过检查点代码（其已有分段记录中顺序最大者）；DNS 为 null
 * @param lastCheckpointPosition 登记时固化的最后通过检查点顺序；DNS 为 null
 * @param revoked                是否已撤销：FALSE-生效中，TRUE-已撤销
 * @param createdAt              登记时间，Unix毫秒时间戳
 * @param revokedAt              撤销时间，Unix毫秒时间戳；未撤销为 null
 */
public record WithdrawalRow(
        long id,
        String withdrawalKey,
        String raceId,
        String bib,
        EntryStatus status,
        String reason,
        String lastCheckpointCode,
        Integer lastCheckpointPosition,
        boolean revoked,
        long createdAt,
        Long revokedAt
) {
}
