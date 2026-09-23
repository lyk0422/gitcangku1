package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.CompensationCheckpointResponse;
import com.example.starter.race.api.CompensationResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerCompensationResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SuspensionEventResponse;
import com.example.starter.race.api.SuspensionHistoryResponse;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingNetRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SuspensionEventRow;

import java.util.ArrayList;
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
            List<CheckpointTimingRow> timings,
            java.util.Map<String, Long> netFinishByBib,
            java.util.Map<String, Long> finishCompensationByBib) {
        List<ResultEntry> entries = ResultCalculator.compute(
                runners, penalties, checkpoints, timings,
                netFinishByBib, finishCompensationByBib);
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
                entry.netFinishTimeMs(),
                entry.penaltyMs(),
                entry.finishCompensationMs(),
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
                entry.finishCompensationMs(),
                entry.totalTimeMs(),
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints());
    }

    /**
     * 组装单个选手的分段明细：按检查点顺序，把已有通过记录映射到对应检查点，
     * 未通过的检查点各时间字段为 null；净分段与补偿取自净值映射（缺省净值=原始值、补偿0）。
     */
    static RunnerTimingResponse runnerTiming(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            java.util.Map<String, CheckpointTimingNetRow> netByCode,
            Long netFinishTimeMs) {
        Map<String, CheckpointTimingRow> byCode = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
                    if (timing == null) {
                        return new CheckpointPassResponse(
                                checkpoint.checkpointCode(), checkpoint.position(),
                                null, null, 0L, null);
                    }
                    CheckpointTimingNetRow net = netByCode.get(checkpoint.checkpointCode());
                    long netElapsed = net == null ? timing.elapsedMillis() : net.netElapsedMs();
                    long compensation = net == null ? 0L : net.compensationMs();
                    return new CheckpointPassResponse(
                            checkpoint.checkpointCode(),
                            checkpoint.position(),
                            timing.elapsedMillis(),
                            netElapsed,
                            compensation,
                            timing.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                race.raceId(), runner.bib(), race.version(), runner.finishTimeMs(),
                netFinishTimeMs, passes);
    }

    /** 由封榜快照中的分段明细组装单选手分段视图（缺失检查点各时间字段为 null）。 */
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
        Long snapshotNetFinish = snapshot.entries().stream()
                .filter(entry -> entry.bib().equals(bib))
                .map(SnapshotEntryRow::netFinishTimeMs)
                .findFirst()
                .orElse(finishTimeMs);
        List<CheckpointPassResponse> passes = checkpoints.stream()
                .map(checkpoint -> {
                    SnapshotCheckpointRow detail = byCode.get(checkpoint.checkpointCode());
                    if (detail == null) {
                        return new CheckpointPassResponse(
                                checkpoint.checkpointCode(), checkpoint.position(),
                                null, null, 0L, null);
                    }
                    return new CheckpointPassResponse(
                            detail.checkpointCode(), detail.position(),
                            detail.elapsedMillis(), detail.netElapsedMs(),
                            detail.compensationMs(), detail.timingId());
                })
                .toList();
        return new RunnerTimingResponse(
                snapshot.raceId(), bib, snapshot.version(), finishTimeMs,
                snapshotNetFinish, passes);
    }

    /**
     * 组装每名选手的中止补偿明细（只读）：逐点补偿取自净分段结果，缺失检查点净值为 null。
     *
     * @param netByBib 每名选手的净计时结果（无已恢复事件时由调用方以0补偿构造）
     */
    static CompensationResponse compensation(
            String raceId,
            int version,
            List<RunnerRow> runners,
            List<CheckpointRow> checkpoints,
            Map<String, com.example.starter.race.domain.NetCalculator.NetRunnerResult> netByBib) {
        List<RunnerCompensationResponse> rows = new java.util.ArrayList<>(runners.size());
        for (RunnerRow runner : runners) {
            com.example.starter.race.domain.NetCalculator.NetRunnerResult net =
                    netByBib.get(runner.bib());
            Map<String, com.example.starter.race.domain.NetCalculator.NetCheckpoint> netByCode =
                    new LinkedHashMap<>();
            if (net != null) {
                for (com.example.starter.race.domain.NetCalculator.NetCheckpoint cp :
                        net.checkpoints()) {
                    netByCode.put(cp.checkpointCode(), cp);
                }
            }
            List<CompensationCheckpointResponse> cps = checkpoints.stream()
                    .map(checkpoint -> {
                        com.example.starter.race.domain.NetCalculator.NetCheckpoint cp =
                                netByCode.get(checkpoint.checkpointCode());
                        if (cp == null) {
                            return new CompensationCheckpointResponse(
                                    checkpoint.checkpointCode(), checkpoint.position(),
                                    null, null, 0L, null);
                        }
                        return new CompensationCheckpointResponse(
                                cp.checkpointCode(), cp.position(),
                                cp.rawElapsedMs(), cp.netElapsedMs(), cp.compensationMs(),
                                cp.timingId());
                    })
                    .toList();
            long finishCompensation = net == null ? 0L : net.finishCompensationMs();
            Long netFinish = net == null ? runner.finishTimeMs() : net.netFinishTimeMs();
            rows.add(new RunnerCompensationResponse(
                    runner.bib(), finishCompensation > 0L, runner.finishTimeMs(),
                    netFinish, finishCompensation, cps));
        }
        return new CompensationResponse(raceId, version, rows);
    }

    /** 组装中止恢复事件历史（只读）。 */
    static SuspensionHistoryResponse suspensionHistory(
            RaceRow race, List<SuspensionEventRow> events) {
        List<SuspensionEventResponse> rows = events.stream()
                .map(ResponseMapper::suspensionEvent)
                .toList();
        return new SuspensionHistoryResponse(
                race.raceId(), race.version(), race.status(), rows);
    }

    /** 组装单个中止恢复事件视图。 */
    static SuspensionEventResponse suspensionEvent(SuspensionEventRow event) {
        return new SuspensionEventResponse(
                event.eventKey(),
                event.checkpointCode(),
                event.checkpointPosition(),
                event.startElapsedMs(),
                event.resumeElapsedMs(),
                event.durationMs(),
                event.status(),
                event.versionAfterSuspend(),
                event.versionAfterResume(),
                event.createdAt(),
                event.resumedAt());
    }

    /**
     * 由封榜快照组装每名选手的补偿明细（只读、冻结）：
     * 逐点原始/净耗时与补偿取自固化的快照分段，缺失检查点净值为 null。
     */
    static CompensationResponse snapshotCompensation(
            SnapshotRow snapshot,
            List<RunnerRow> runners,
            List<CheckpointRow> checkpoints) {
        Map<String, List<SnapshotCheckpointRow>> cpsByBib = new LinkedHashMap<>();
        for (SnapshotCheckpointRow detail : snapshot.checkpoints()) {
            cpsByBib.computeIfAbsent(detail.bib(), key -> new ArrayList<>()).add(detail);
        }
        Map<String, SnapshotEntryRow> entryByBib = new LinkedHashMap<>();
        for (SnapshotEntryRow entry : snapshot.entries()) {
            entryByBib.put(entry.bib(), entry);
        }
        List<RunnerCompensationResponse> rows = new ArrayList<>(runners.size());
        for (RunnerRow runner : runners) {
            SnapshotEntryRow entry = entryByBib.get(runner.bib());
            long finishCompensation = entry == null ? 0L : entry.finishCompensationMs();
            Long netFinish = entry == null ? runner.finishTimeMs() : entry.netFinishTimeMs();
            Map<String, SnapshotCheckpointRow> byCode = new LinkedHashMap<>();
            for (SnapshotCheckpointRow detail : cpsByBib.getOrDefault(runner.bib(), List.of())) {
                byCode.put(detail.checkpointCode(), detail);
            }
            List<CompensationCheckpointResponse> cps = checkpoints.stream()
                    .map(checkpoint -> {
                        SnapshotCheckpointRow detail = byCode.get(checkpoint.checkpointCode());
                        if (detail == null) {
                            return new CompensationCheckpointResponse(
                                    checkpoint.checkpointCode(), checkpoint.position(),
                                    null, null, 0L, null);
                        }
                        return new CompensationCheckpointResponse(
                                detail.checkpointCode(), detail.position(),
                                detail.elapsedMillis(), detail.netElapsedMs(),
                                detail.compensationMs(), detail.timingId());
                    })
                    .toList();
            rows.add(new RunnerCompensationResponse(
                    runner.bib(), finishCompensation > 0L, runner.finishTimeMs(),
                    netFinish, finishCompensation, cps));
        }
        return new CompensationResponse(snapshot.raceId(), snapshot.version(), rows);
    }
}
