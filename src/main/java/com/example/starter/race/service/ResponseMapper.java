package com.example.starter.race.service;

import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.SplitDetailResponse;
import com.example.starter.race.api.SplitTimeResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SplitTimeRow;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            Set<String> missingCheckpointBibs,
            Map<String, List<SplitDetailResponse>> splitsByBib) {
        List<ResultEntry> entries =
                ResultCalculator.compute(runners, penalties, missingCheckpointBibs);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream()
                        .map(entry -> toEntryResponse(
                                entry, splitsOf(splitsByBib, entry.bib())))
                        .toList());
    }

    static StandingResponse snapshotStanding(
            SnapshotRow snapshot, Map<String, List<SplitDetailResponse>> splitsByBib) {
        return new StandingResponse(
                snapshot.raceId(),
                snapshot.version(),
                RaceStatus.SEALED,
                snapshot.sealedAt(),
                snapshot.entries().stream()
                        .map(entry -> toEntryResponse(
                                entry, splitsOf(splitsByBib, entry.bib())))
                        .toList());
    }

    static SplitTimeResponse toSplitTimeResponse(SplitTimeRow row) {
        return new SplitTimeResponse(row.timingId(), row.raceId(), row.bib(),
                row.checkpointCode(), row.seq(), row.elapsedMs(), row.createdAt());
    }

    private static List<SplitDetailResponse> splitsOf(
            Map<String, List<SplitDetailResponse>> splitsByBib, String bib) {
        return splitsByBib == null ? null : splitsByBib.get(bib);
    }

    static ResultEntryResponse toEntryResponse(ResultEntry entry) {
        return toEntryResponse(entry, null);
    }

    static ResultEntryResponse toEntryResponse(ResultEntry entry, List<SplitDetailResponse> splits) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                splits);
    }

    static ResultEntryResponse toEntryResponse(SnapshotEntryRow entry) {
        return toEntryResponse(entry, null);
    }

    static ResultEntryResponse toEntryResponse(
            SnapshotEntryRow entry, List<SplitDetailResponse> splits) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                splits);
    }
}
