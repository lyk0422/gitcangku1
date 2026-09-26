package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.MedicalHoldResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.ExclusionReason;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.MedicalHoldRow;
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
        return new RunnerResponse(row.bib(), row.finishTimeMs(), row.withdrawn(),
                row.withdrawnAt(), row.createdAt(), row.updatedAt());
    }

    static PenaltyResponse toPenaltyResponse(PenaltyRow row) {
        return new PenaltyResponse(row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.revoked(), row.createdAt(), row.revokedAt());
    }

    static CheckpointTimingResponse toTimingResponse(CheckpointTimingRow row) {
        return new CheckpointTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), row.medicalHold(), row.holdId(), row.createdAt());
    }

    static MedicalHoldResponse toMedicalHoldResponse(MedicalHoldRow row) {
        Long durationMs = row.endAt() == null ? null : row.endAt() - row.startAt();
        return new MedicalHoldResponse(
                row.holdId(), row.raceId(), row.bib(), row.status(), row.startAt(),
                row.endAt(), durationMs, row.reason(), row.startedBy(), row.resumedBy(),
                row.fitnessConclusion(), row.createdAt(), row.resumedAt());
    }

    static StandingResponse liveStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            List<MedicalHoldRow> activeHolds) {
        List<ResultEntry> entries = ResultCalculator.compute(
                standingRunnerViews(runners, activeHolds), penalties, checkpoints, timings);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /** 实时成绩/封榜共用的选手视图：在 runner 行基础上叠加“是否存在生效中医疗暂停”。 */
    static List<ResultCalculator.RunnerView> standingRunnerViews(
            List<RunnerRow> runners, List<MedicalHoldRow> activeHolds) {
        Map<String, Boolean> holdActiveByBib = new LinkedHashMap<>();
        for (MedicalHoldRow hold : activeHolds) {
            holdActiveByBib.put(hold.bib(), Boolean.TRUE);
        }
        return runners.stream()
                .map(runner -> (ResultCalculator.RunnerView) new StandingRunnerView(
                        runner, holdActiveByBib.containsKey(runner.bib())))
                .toList();
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
                            timing == null ? null : timing.timingId(),
                            timing != null && timing.medicalHold()
                                    ? ExclusionReason.MEDICAL_HOLD : null);
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
                                checkpoint.checkpointCode(), checkpoint.position(),
                                null, null, null);
                    }
                    return new CheckpointPassResponse(
                            detail.checkpointCode(), detail.position(),
                            detail.elapsedMillis(), detail.timingId(), detail.exclusionReason());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs, passes);
    }

    /** 实时成绩用的选手视图：在 runner 行基础上叠加“是否存在生效中医疗暂停”。 */
    private record StandingRunnerView(
            RunnerRow row,
            boolean holdActive
    ) implements ResultCalculator.RunnerView {
        @Override
        public String bib() {
            return row.bib();
        }

        @Override
        public Long finishTimeMs() {
            return row.finishTimeMs();
        }

        @Override
        public boolean withdrawn() {
            return row.withdrawn();
        }

        @Override
        public boolean medicalHoldActive() {
            return holdActive;
        }
    }
}
