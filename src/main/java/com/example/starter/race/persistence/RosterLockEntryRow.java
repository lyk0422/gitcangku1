package com.example.starter.race.persistence;

/**
 * roster_lock_entry 表行记录：锁定版本固化的成员及其锁定时刻个人成绩快照。
 *
 * <p>个人成绩归属在锁定时固化到名单版本：此后计时修订、处罚变更、检查点提交乃至退赛
 * 都不改变本行数值；团队成绩按该版本汇总。
 *
 * @param raceId                    所属赛事ID
 * @param teamId                    团队ID
 * @param rosterVersion             名单版本号
 * @param bib                       成员参赛号
 * @param entryStatus               锁定时刻个人成绩状态
 * @param finishTimeMs              锁定时刻原始完赛耗时（毫秒）；计时缺失为 null
 * @param penaltyMs                 锁定时刻生效（未撤销）加时合计毫秒数，无加时为0
 * @param totalTimeMs               锁定时刻总耗时（毫秒）；未排名为 null
 * @param checkpointCount           赛事检查点总数；未配置检查点为0
 * @param coveredCheckpointCount    锁定时刻该成员已覆盖检查点数量
 */
public record RosterLockEntryRow(
        String raceId,
        String teamId,
        int rosterVersion,
        String bib,
        com.example.starter.race.domain.EntryStatus entryStatus,
        Long finishTimeMs,
        long penaltyMs,
        Long totalTimeMs,
        int checkpointCount,
        int coveredCheckpointCount
) implements com.example.starter.race.domain.TeamScoreCalculator.RosterMemberView {

    @Override
    public com.example.starter.race.domain.EntryStatus status() {
        return entryStatus;
    }
}
