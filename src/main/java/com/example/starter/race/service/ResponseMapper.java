package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.CurrentRosterMemberResponse;
import com.example.starter.race.api.CurrentRosterResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RosterMemberResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RosterMemberState;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TeamStanding;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.TeamMembershipRow;
import com.example.starter.race.persistence.TeamRosterMemberRow;
import com.example.starter.race.persistence.TeamRosterRow;
import com.example.starter.race.persistence.TeamStandingSnapshotRow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 持久化行记录与 API 响应之间的转换。
 */
final class ResponseMapper {

    private ResponseMapper() {
    }

    static RunnerResponse toRunnerResponse(RunnerRow row) {
        return new RunnerResponse(row.bib(), row.finishTimeMs(), row.createdAt(), row.updatedAt());
    }

    static PenaltyResponse toPenaltyResponse(PenaltyRow row) {
        return new PenaltyResponse(row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.revoked(), row.createdAt(), row.revokedAt());
    }

    static CheckpointTimingResponse toTimingResponse(CheckpointTimingRow row) {
        return new CheckpointTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), row.createdAt());
    }

    static StandingResponse liveStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints, timings);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    static StandingResponse snapshotStanding(SnapshotRow snapshot) {
        return new StandingResponse(
                snapshot.raceId(),
                snapshot.version(),
                RaceStatus.SEALED,
                snapshot.sealedAt(),
                snapshot.entries().stream().map(ResponseMapper::toEntryResponse).toList());
    }

    static ResultEntryResponse toEntryResponse(ResultEntry entry) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    static ResultEntryResponse toEntryResponse(SnapshotEntryRow entry) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    /**
     * 组装单个选手的分段明细：按检查点顺序，把已有通过记录映射到对应检查点，
     * 未通过的检查点 elapsedMillis/timingId 为 null。
     */
    static RunnerTimingResponse runnerTiming(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        Map<String, CheckpointTimingRow> byCode = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
                    return new CheckpointPassResponse(
                            checkpoint.checkpointCode(),
                            checkpoint.position(),
                            timing == null ? null : timing.elapsedMillis(),
                            timing == null ? null : timing.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                race.raceId(), runner.bib(), race.version(), runner.finishTimeMs(), passes);
    }

    /** 由封榜快照中的分段明细组装单选手分段视图（缺失检查点 elapsedMillis/timingId 为 null）。 */
    static RunnerTimingResponse snapshotRunnerTiming(
            SnapshotRow snapshot,
            String bib,
            Long finishTimeMs,
            List<CheckpointRow> checkpoints) {
        Map<String, SnapshotCheckpointRow> byCode = new LinkedHashMap<>();
        for (SnapshotCheckpointRow detail : snapshot.checkpoints()) {
            if (detail.bib().equals(bib)) {
                byCode.put(detail.checkpointCode(), detail);
            }
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    SnapshotCheckpointRow detail = byCode.get(checkpoint.checkpointCode());
                    if (detail == null) {
                        return new CheckpointPassResponse(
                                checkpoint.checkpointCode(), checkpoint.position(), null, null);
                    }
                    return new CheckpointPassResponse(
                            detail.checkpointCode(), detail.position(),
                            detail.elapsedMillis(), detail.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs, passes);
    }

    /** 已锁定名单版本头 + 固化成员 → 只读名单响应。 */
    static TeamRosterResponse toRosterResponse(TeamRosterRow roster) {
        return new TeamRosterResponse(
                roster.raceId(),
                roster.teamId(),
                roster.versionNo(),
                roster.lockedAt(),
                roster.teamPoints(),
                roster.members().stream()
                        .map(ResponseMapper::toRosterMemberResponse)
                        .toList());
    }

    static RosterMemberResponse toRosterMemberResponse(TeamRosterMemberRow member) {
        return new RosterMemberResponse(
                member.bib(),
                member.rankNo(),
                member.entryStatus(),
                member.finishTimeMs(),
                member.penaltyMs(),
                member.totalTimeMs(),
                member.memberPoints());
    }

    /**
     * 组装当前名单：以最新锁定版本的固化成员为准，叠加归属表中的退赛状态；
     * 历史版本中已被替换、不在当前版本的成员不再出现，但归属行保留（不可删人、不可跨队）。
     */
    static CurrentRosterResponse toCurrentRosterResponse(
            TeamRosterRow latestRoster,
            java.util.Map<String, TeamMembershipRow> membershipByBib) {
        List<CurrentRosterMemberResponse> members = latestRoster.members().stream()
                .map(member -> {
                    TeamMembershipRow membership = membershipByBib.get(member.bib());
                    boolean withdrawn = membership != null && membership.withdrawnAt() != null;
                    return new CurrentRosterMemberResponse(
                            member.bib(),
                            withdrawn
                                    ? RosterMemberState.WITHDRAWN.name()
                                    : RosterMemberState.ACTIVE.name(),
                            membership == null ? latestRoster.versionNo() : membership.firstVersionNo(),
                            latestRoster.versionNo(),
                            membership == null ? null : membership.effectiveSession(),
                            membership == null ? null : membership.withdrawalReason(),
                            membership == null ? null : membership.withdrawnAt(),
                            member.memberPoints());
                })
                .toList();
        return new CurrentRosterResponse(
                latestRoster.raceId(), latestRoster.teamId(),
                latestRoster.versionNo(), members);
    }

    /** 领域团队榜 → 团队成绩响应。 */
    static TeamStandingsResponse toTeamStandingsResponse(
            String raceId,
            int version,
            RaceStatus status,
            List<TeamStanding> standings) {
        return new TeamStandingsResponse(
                raceId, version, status,
                standings.stream()
                        .map(standing -> new TeamStandingsResponse.TeamStandingResponse(
                                standing.teamId(),
                                standing.rosterVersionNo(),
                                standing.teamPoints(),
                                standing.memberCount(),
                                standing.rankNo()))
                        .toList());
    }

    /** 封榜团队榜行 → 团队成绩响应（只读）。 */
    static TeamStandingsResponse toTeamSnapshotResponse(
            String raceId, int version, List<TeamStandingSnapshotRow> rows) {
        return new TeamStandingsResponse(
                raceId, version, RaceStatus.SEALED,
                rows.stream()
                        .map(row -> new TeamStandingsResponse.TeamStandingResponse(
                                row.teamId(),
                                row.rosterVersionNo(),
                                row.teamPoints(),
                                row.memberCount(),
                                row.rankNo()))
                        .toList());
    }
}
