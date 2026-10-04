package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.LapHistoryEntryResponse;
import com.example.starter.race.api.LapHistoryResponse;
import com.example.starter.race.api.LapPassResponse;
import com.example.starter.race.api.LapPointResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerLapsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.LapPoint;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.LapPassHistoryRow;
import com.example.starter.race.persistence.LapPassRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;

import java.util.ArrayList;
import java.util.HashMap;
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

    static LapPassResponse toLapPassResponse(LapPassRow row) {
        return new LapPassResponse(row.lapPassId(), row.bib(), row.lapNo(), row.point(),
                row.elapsedMillis(), row.createdBy(), row.createdAt());
    }

    /** 绕圈赛事即时成绩：按末圈FINISH加生效处罚排名，漏圈或逆序标记 LAP_INCOMPLETE。 */
    static StandingResponse liveLoopStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<LapPassRow> lapPasses) {
        List<ResultEntry> entries = ResultCalculator.computeLoop(
                runners, penalties, race.totalLaps(), lapPasses);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /**
     * 组装选手圈次总览：按圈号与点序展开全部 总圈数×3 个点，
     * 未提交的点记录ID与耗时为 null；complete 要求全部点齐备且全赛程耗时严格递增。
     */
    static RunnerLapsResponse runnerLaps(RaceRow race, String bib, List<LapPassRow> passes) {
        Map<Integer, LapPassRow> bySequence = new HashMap<>();
        for (LapPassRow pass : passes) {
            bySequence.put(LapPoint.sequence(pass.lapNo(), pass.point()), pass);
        }
        List<LapPointResponse> laps = new ArrayList<>();
        boolean complete = true;
        long previousElapsed = -1L;
        for (int lap = 1; lap <= race.totalLaps(); lap++) {
            for (LapPoint point : LapPoint.values()) {
                LapPassRow pass = bySequence.get(LapPoint.sequence(lap, point));
                if (pass == null) {
                    complete = false;
                    laps.add(new LapPointResponse(lap, point, null, null));
                } else {
                    if (pass.elapsedMillis() <= previousElapsed) {
                        complete = false;
                    }
                    previousElapsed = pass.elapsedMillis();
                    laps.add(new LapPointResponse(
                            lap, point, pass.lapPassId(), pass.elapsedMillis()));
                }
            }
        }
        return new RunnerLapsResponse(
                race.raceId(), bib, race.version(), race.totalLaps(), complete, laps);
    }

    /** 组装选手圈次更正历史（仓储已按圈号、点序、更正时间排序）。 */
    static LapHistoryResponse lapHistory(
            String raceId, String bib, List<LapPassHistoryRow> history) {
        return new LapHistoryResponse(raceId, bib, history.stream()
                .map(row -> new LapHistoryEntryResponse(
                        row.historyId(), row.replacedBy(), row.lapNo(), row.point(),
                        row.elapsedMillis(), row.createdBy(), row.createdAt(),
                        row.correctedBy(), row.correctedAt()))
                .toList());
    }
}