package com.example.starter.race.service;

import com.example.starter.race.api.RosterMemberResponse;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.TeamMemberScoreResponse;
import com.example.starter.race.api.TeamScoreResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.WithdrawalResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.TeamScoreCalculator;
import com.example.starter.race.persistence.RosterLockEntryRow;
import com.example.starter.race.persistence.RosterLockRow;
import com.example.starter.race.persistence.RosterMembershipRow;
import com.example.starter.race.persistence.SnapshotTeamMemberRow;
import com.example.starter.race.persistence.SnapshotTeamRow;
import com.example.starter.race.persistence.WithdrawalRow;

import java.util.List;
import java.util.Map;

/**
 * 团队名单领域持久化行与 API 响应之间的转换。
 */
final class TeamRosterResponseMapper {

    private TeamRosterResponseMapper() {
    }

    /** 组装当前名单：最新锁定版本成员合并成员当前退赛状态。 */
    static RosterResponse currentRoster(
            String raceId,
            RosterLockRow lock,
            List<RosterLockEntryRow> entries,
            Map<String, RosterMembershipRow> membershipByBib,
            Map<String, WithdrawalRow> withdrawalByBib,
            List<WithdrawalRow> teamWithdrawals) {
        List<RosterMemberResponse> members = entries.stream()
                .map(entry -> toMemberResponse(
                        entry, membershipByBib.get(entry.bib()), withdrawalByBib.get(entry.bib())))
                .toList();
        return new RosterResponse(
                raceId,
                lock.teamId(),
                lock.rosterVersion(),
                lock.lockedAt(),
                members,
                true,
                teamWithdrawals.stream().map(TeamRosterResponseMapper::toWithdrawalResponse).toList());
    }

    /** 组装历史锁定版本：原样返回封版内容，不附带后续退赛状态。 */
    static RosterResponse rosterVersion(String raceId, RosterLockRow lock,
                                        List<RosterLockEntryRow> entries, boolean current) {
        List<RosterMemberResponse> members = entries.stream()
                .map(entry -> new RosterMemberResponse(
                        entry.bib(), lock.rosterVersion(), entry.entryStatus(),
                        entry.finishTimeMs(), entry.penaltyMs(), entry.totalTimeMs(),
                        false, null, null, null))
                .toList();
        return new RosterResponse(
                raceId, lock.teamId(), lock.rosterVersion(), lock.lockedAt(),
                members, current, List.of());
    }

    static RosterMemberResponse toMemberResponse(
            RosterLockEntryRow entry,
            RosterMembershipRow membership,
            WithdrawalRow withdrawal) {
        // 成员首次锁定进队的版本以归属表为准；版本内重复出现的老成员不能显示成新版本加入。
        int firstVersion = membership == null ? entry.rosterVersion() : membership.rosterVersion();
        if (withdrawal == null) {
            return new RosterMemberResponse(
                    entry.bib(), firstVersion, entry.entryStatus(), entry.finishTimeMs(),
                    entry.penaltyMs(), entry.totalTimeMs(), false, null, null, null);
        }
        return new RosterMemberResponse(
                entry.bib(),
                firstVersion,
                entry.entryStatus(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                true,
                withdrawal.effectiveSession(),
                withdrawal.reason(),
                withdrawal.withdrawnAt());
    }

    static WithdrawalResponse toWithdrawalResponse(WithdrawalRow row) {
        return new WithdrawalResponse(
                row.bib(), row.teamId(), row.effectiveSession(),
                row.reason(), row.withdrawnAt());
    }

    /** 实时团队成绩榜响应。 */
    static TeamStandingResponse liveStanding(
            String raceId,
            int raceVersion,
            List<TeamScoreCalculator.TeamScore> scores) {
        return new TeamStandingResponse(
                raceId, raceVersion, RaceStatus.OPEN, null,
                scores.stream().map(TeamRosterResponseMapper::toScoreResponse).toList());
    }

    /** 封榜团队只读快照响应。 */
    static TeamStandingResponse snapshotStanding(
            String raceId,
            int raceVersion,
            long sealedAt,
            List<SnapshotTeamRow> teams,
            Map<String, List<SnapshotTeamMemberRow>> membersByTeam) {
        List<TeamScoreResponse> scores = teams.stream()
                .map(team -> new TeamScoreResponse(
                        team.teamId(),
                        team.rosterVersion(),
                        team.totalTimeMs(),
                        team.memberCount(),
                        team.scoringCount(),
                        membersByTeam.getOrDefault(team.teamId(), List.of()).stream()
                                .map(TeamRosterResponseMapper::toMemberScoreResponse)
                                .toList()))
                .toList();
        return new TeamStandingResponse(
                raceId, raceVersion, RaceStatus.SEALED, sealedAt, scores);
    }

    static TeamScoreResponse toScoreResponse(TeamScoreCalculator.TeamScore score) {
        return new TeamScoreResponse(
                score.teamId(),
                score.rosterVersion(),
                score.totalTimeMs(),
                score.memberCount(),
                score.scoringCount(),
                score.members().stream().map(TeamRosterResponseMapper::toMemberScoreResponse).toList());
    }

    static TeamMemberScoreResponse toMemberScoreResponse(TeamScoreCalculator.MemberScore member) {
        return new TeamMemberScoreResponse(
                member.bib(),
                member.status(),
                member.finishTimeMs(),
                member.penaltyMs(),
                member.totalTimeMs(),
                member.scoring(),
                member.withdrawn(),
                member.effectiveSession());
    }

    static TeamMemberScoreResponse toMemberScoreResponse(SnapshotTeamMemberRow row) {
        return new TeamMemberScoreResponse(
                row.bib(),
                row.entryStatus(),
                row.finishTimeMs(),
                row.penaltyMs(),
                row.totalTimeMs(),
                row.scoring(),
                row.withdrawn(),
                row.effectiveSession());
    }
}
