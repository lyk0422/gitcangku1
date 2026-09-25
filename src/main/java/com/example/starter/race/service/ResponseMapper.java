package com.example.starter.race.service;

import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.RelayStandingResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.RelayStanding;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RelaySnapshotRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;

import java.util.List;

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

    static StandingResponse liveStanding(
            RaceRow race, List<RunnerRow> runners, List<PenaltyRow> penalties) {
        List<ResultEntry> entries = ResultCalculator.compute(runners, penalties);
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
                entry.totalTimeMs());
    }

    static ResultEntryResponse toEntryResponse(SnapshotEntryRow entry) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs());
    }

    /** 接力即时排名（OPEN）。 */
    static RelayStandingResponse relayLiveStanding(RaceRow race, List<RelayStanding> standings) {
        return new RelayStandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                standings.stream().map(ResponseMapper::toRelayEntry).toList());
    }

    /** 接力封榜快照排名（SEALED）。 */
    static RelayStandingResponse relaySnapshotStanding(RelaySnapshotRow snapshot) {
        List<RelayStandingResponse.Entry> entries = snapshot.teams().stream()
                .map(team -> new RelayStandingResponse.Entry(
                        team.teamKey(),
                        team.rank(),
                        team.status(),
                        team.totalMs(),
                        team.foulCount(),
                        team.foulCount() > 0))
                .toList();
        return new RelayStandingResponse(
                snapshot.raceId(),
                snapshot.version(),
                RaceStatus.SEALED,
                snapshot.sealedAt(),
                entries);
    }

    private static RelayStandingResponse.Entry toRelayEntry(RelayStanding standing) {
        return new RelayStandingResponse.Entry(
                standing.teamKey(),
                standing.rank(),
                standing.status(),
                standing.totalMs(),
                standing.foulCount(),
                standing.hasFouls());
    }
}
