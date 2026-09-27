package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.ExcludedTimingResponse;
import com.example.starter.race.api.FinishEventInHoldResponse;
import com.example.starter.race.api.MedicalHoldHistoryItem;
import com.example.starter.race.api.MedicalHoldResumptionResponse;
import com.example.starter.race.api.MedicalHoldResponse;
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
import com.example.starter.race.persistence.MedicalHoldDetailRow;
import com.example.starter.race.persistence.MedicalHoldResumptionRow;
import com.example.starter.race.persistence.MedicalHoldRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerFinishEventRow;
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
        return new RunnerResponse(row.bib(), row.finishTimeMs(), row.createdAt(), row.updatedAt(),
                row.withdrawn(), row.withdrawnAt());
    }

    static PenaltyResponse toPenaltyResponse(PenaltyRow row) {
        return new PenaltyResponse(row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.revoked(), row.createdAt(), row.revokedAt());
    }

    static CheckpointTimingResponse toTimingResponse(CheckpointTimingRow row) {
        String excludedHoldId = row.excludedHoldId();
        return new CheckpointTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), excludedHoldId != null, excludedHoldId, row.createdAt());
    }

    static MedicalHoldResponse toMedicalHoldResponse(MedicalHoldRow row) {
        return new MedicalHoldResponse(
                row.holdId(), row.raceId(), row.bib(), row.startAt(),
                row.reason(), row.startedBy(), row.createdAt());
    }

    static MedicalHoldResumptionResponse toResumptionResponse(
            MedicalHoldRow hold, MedicalHoldResumptionRow resumption) {
        return new MedicalHoldResumptionResponse(
                hold.holdId(), hold.raceId(), hold.bib(), hold.startAt(),
                resumption.endAt(), resumption.endAt() - hold.startAt(),
                resumption.resumedBy(), resumption.fitConclusion(), resumption.resumedAt());
    }

    static StandingResponse liveStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            List<MedicalHoldDetailRow> medicalHolds) {
        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints, timings, medicalHolds);
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
                entry.missingCheckpoints(),
                entry.medicalHoldActive(),
                entry.excludedTimingCount());
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
                entry.missingCheckpoints(),
                entry.medicalHoldActive(),
                entry.excludedTimingCount());
    }

    /**
     * 组装单个选手的分段明细：按检查点顺序，把已有通过记录映射到对应检查点，
     * 未通过的检查点 elapsedMillis/timingId 为 null；被医疗暂停排除的分段带排除原因。
     */
    static RunnerTimingResponse runnerTiming(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            Map<String, String> holdReasons) {
        Map<String, CheckpointTimingRow> byCode = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
                    if (timing == null) {
                        return new CheckpointPassResponse(
                                checkpoint.checkpointCode(), checkpoint.position(), null, null);
                    }
                    String excludedHoldId = timing.excludedHoldId();
                    return new CheckpointPassResponse(
                            checkpoint.checkpointCode(),
                            checkpoint.position(),
                            timing.elapsedMillis(),
                            timing.timingId(),
                            excludedHoldId != null,
                            excludedHoldId,
                            excludedHoldId == null ? null : holdReasons.get(excludedHoldId));
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
                    String excludedHoldId = detail.excludedHoldId();
                    return new CheckpointPassResponse(
                            detail.checkpointCode(), detail.position(),
                            detail.elapsedMillis(), detail.timingId(),
                            excludedHoldId != null, excludedHoldId, detail.excludedReason());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs, passes);
    }

    /** 被排除分段响应（按调用方给定顺序）。 */
    static ExcludedTimingResponse toExcludedTimingResponse(CheckpointTimingRow row) {
        return new ExcludedTimingResponse(
                row.timingId(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), row.excludedHoldId(), row.createdAt());
    }

    /** 组装一条医疗暂停历史项。 */
    static MedicalHoldHistoryItem toHistoryItem(
            MedicalHoldDetailRow detail, int excludedTimingCount) {
        boolean active = detail.endAt() == null;
        return new MedicalHoldHistoryItem(
                detail.holdId(),
                detail.bib(),
                detail.startAt(),
                detail.reason(),
                detail.startedBy(),
                detail.createdAt(),
                active,
                detail.endAt(),
                detail.endAt() == null ? null : detail.endAt() - detail.startAt(),
                detail.resumedBy(),
                detail.fitConclusion(),
                detail.resumedAt(),
                excludedTimingCount);
    }

    /** 暂停区间内终点计时事件诊断项。 */
    static FinishEventInHoldResponse toFinishEventInHoldResponse(
            MedicalHoldDetailRow hold, RunnerFinishEventRow event) {
        return new FinishEventInHoldResponse(
                hold.holdId(), hold.startAt(), hold.endAt(),
                event.finishTimeMs(), event.requestId(), event.createdAt());
    }
}
