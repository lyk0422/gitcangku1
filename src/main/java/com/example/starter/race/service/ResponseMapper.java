package com.example.starter.race.service;

import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.AppealSegmentResponse;
import com.example.starter.race.api.AppealsResponse;
import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.FrozenResultResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyAppealRow;
import com.example.starter.race.persistence.PenaltyAppealSegmentRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;

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
        return new PenaltyResponse(
                row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.version(), row.superseded(), row.supersedesPenaltyId(),
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
            List<CheckpointTimingRow> timings,
            java.util.Set<String> pendingAppealBibs) {
        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints, timings);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream()
                        .map(entry -> toLiveEntryResponse(entry, pendingAppealBibs))
                        .toList());
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

    /** 实时成绩条目，并标记该选手是否存在待决申诉。 */
    static ResultEntryResponse toLiveEntryResponse(ResultEntry entry, java.util.Set<String> pendingBibs) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                entry.finishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints(),
                pendingBibs.contains(entry.bib()));
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

    /**
     * 组装申诉证据响应：受理冻结成绩/分段、两人意见与重算前后榜单快照（由服务层反序列化后传入）。
     */
    static AppealResponse toAppealResponse(
            PenaltyAppealRow row,
            List<PenaltyAppealSegmentRow> segments,
            StandingResponse beforeLeaderboard,
            StandingResponse afterLeaderboard) {
        FrozenResultResponse frozenResult = new FrozenResultResponse(
                row.frozenLeaderboardVersion(),
                row.frozenFinishTimeMs(),
                row.frozenPenaltyMs(),
                row.frozenTotalTimeMs(),
                row.frozenRank(),
                row.frozenEntryStatus());
        List<AppealSegmentResponse> segmentResponses = segments.stream()
                .map(segment -> new AppealSegmentResponse(
                        segment.checkpointCode(),
                        segment.position(),
                        segment.elapsedMillis(),
                        segment.timingId()))
                .toList();
        return new AppealResponse(
                row.appealKey(),
                row.raceId(),
                row.bib(),
                row.penaltyId(),
                row.status(),
                row.reason(),
                row.timingVersion(),
                row.segmentVersion(),
                row.finishAtMs(),
                frozenResult,
                segmentResponses,
                row.firstStewardId(),
                row.firstRecommendation(),
                row.firstReplacementMs(),
                row.firstRecordedAt(),
                row.secondStewardId(),
                row.secondAction(),
                row.secondRecordedAt(),
                row.newPenaltyId(),
                beforeLeaderboard,
                afterLeaderboard,
                row.createdAt(),
                row.decidedAt());
    }
}
