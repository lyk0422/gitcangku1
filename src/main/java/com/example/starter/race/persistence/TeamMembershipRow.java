package com.example.starter.race.persistence;

/**
 * team_membership 表行记录：成员在同一赛事内的唯一团队归属。
 *
 * <p>主键 (raceId, bib) 即唯一键，保证成员同一赛事不得跨队；退赛只回填退赛字段，不删除本行。
 *
 * @param raceId           所属赛事ID
 * @param bib              参赛号，同一赛事内只能归属一个团队
 * @param teamId           归属团队ID，锁定后不可变更
 * @param firstVersionNo   首次被该团队锁定的名单版本号
 * @param lockedAt         首次入队锁定时间，Unix毫秒时间戳
 * @param effectiveSession 退赛生效场次代码；未退赛为 null
 * @param withdrawalReason 退赛原因；未退赛为 null
 * @param withdrawnAt      退赛记录时间，Unix毫秒时间戳；未退赛为 null
 */
public record TeamMembershipRow(
        String raceId,
        String bib,
        String teamId,
        int firstVersionNo,
        long lockedAt,
        String effectiveSession,
        String withdrawalReason,
        Long withdrawnAt
) {
}
