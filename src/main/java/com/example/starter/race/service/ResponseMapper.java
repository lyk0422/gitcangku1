package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SuspensionEventResponse;
import com.example.starter.race.domain.NetTimeCalculator;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceEventRow;
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
        return new PenaltyResponse(row.penaltyId(), row.bib(), row.type(), row.amountMs(),
                row.revoked(), row.createdAt(), row.revokedAt());
    }

    static CheckpointTimingResponse toTimingResponse(CheckpointTimingRow row) {
        return new CheckpointTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.elapsedMillis(), row.createdAt());
    }

    static SuspensionEventResponse toEventResponse(RaceEventRow row) {
        Long duration = row.resumeElapsedMs() == null
                ? null : row.resumeElapsedMs() - row.startElapsedMs();
        return new SuspensionEventResponse(
                row.eventKey(), row.raceId(), row.checkpointKey(), row.startElapsedMs(),
                row.resumeElapsedMs(), duration, row.status(), row.createdAt(), row.resumedAt());
    }

    /**
     * 即时成绩：以净完赛耗时作为排名依据（无中止事件时净值等于原始值），
     * 响应同时携带原始完赛耗时与净完赛耗时。
     */
    static StandingResponse liveStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            Map<String, NetTimeCalculator.NetRunnerResult> nets) {
        List<ResultCalculator.RunnerView> effectiveRunners = runners.stream()
                .map(runner -> (ResultCalculator.RunnerView) new EffectiveRunner(
                        runner.bib(), nets.get(runner.bib()).netFinishMs()))
                .toList();
        List<ResultEntry> entries = ResultCalculator.compute(
                effectiveRunners, penalties, checkpoints, timings);
        Map<String, Long> rawFinishByBib = new LinkedHashMap<>();
        for (RunnerRow runner : runners) {
            rawFinishByBib.put(runner.bib(), runner.finishTimeMs());
        }
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream()
                        .map(entry -> toNetEntryResponse(entry, rawFinishByBib.get(entry.bib())))
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

    /**
     * 即时成绩条目映射：entry 的 finishTimeMs/totalTimeMs 为净口径（排名依据），
     * rawFinishMs 为该选手原始完赛耗时。
     */
    private static ResultEntryResponse toNetEntryResponse(ResultEntry entry, Long rawFinishMs) {
        return new ResultEntryResponse(
                entry.bib(),
                entry.rank(),
                entry.status(),
                rawFinishMs,
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
                entry.netFinishTimeMs(),
                entry.penaltyMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    /**
     * 组装单个选手的分段明细：按检查点顺序，把已有通过记录映射到对应检查点，
     * 未通过的检查点 elapsedMillis/netElapsedMillis/timingId 为 null。
     */
    static RunnerTimingResponse runnerTiming(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            NetTimeCalculator.NetRunnerResult net) {
        Map<String, CheckpointTimingRow> byCode = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
                    Long netElapsed = timing == null
                            ? null : net.netElapsedByCheckpoint().get(checkpoint.checkpointCode());
                    return new CheckpointPassResponse(
                            checkpoint.checkpointCode(),
                            checkpoint.position(),
                            timing == null ? null : timing.elapsedMillis(),
                            netElapsed,
                            timing == null ? null : timing.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                race.raceId(), runner.bib(), race.version(), runner.finishTimeMs(),
                net.netFinishMs(), passes);
    }

    /** 由封榜快照中的分段明细组装单选手分段视图（缺失检查点各耗时字段为 null）。 */
    static RunnerTimingResponse snapshotRunnerTiming(
            SnapshotRow snapshot,
            String bib,
            Long finishTimeMs,
            Long netFinishTimeMs,
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
                            detail.elapsedMillis(), detail.netElapsedMillis(), detail.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs, netFinishTimeMs, passes);
    }

    /** 排名用的有效选手视图：finishTimeMs 为净完赛耗时。 */
    private record EffectiveRunner(String bib, Long finishTimeMs)
            implements ResultCalculator.RunnerView {
    }
}
