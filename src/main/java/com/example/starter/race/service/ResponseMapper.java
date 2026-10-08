package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointPassResponse;
import com.example.starter.race.api.CheckpointTimingResponse;
import com.example.starter.race.api.IntervalPassResponse;
import com.example.starter.race.api.IntervalTimingResponse;
import com.example.starter.race.api.PenaltyResponse;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.RunnerIntervalsResponse;
import com.example.starter.race.api.RunnerResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.IntervalRankCalculator;
import com.example.starter.race.domain.IntervalResultEntry;
import com.example.starter.race.domain.IntervalRules;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TimingMode;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IntervalTimingRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotIntervalRow;
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
                race.timingMode(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /** 区间模式即时成绩：名次以可证明的范围 [rankBest, rankWorst] 输出，不给确定名次。 */
    static StandingResponse liveIntervalStanding(
            RaceRow race,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<IntervalTimingRow> intervals) {
        List<IntervalResultEntry> entries = IntervalRankCalculator.compute(
                runners, penalties, checkpoints, intervals);
        return new StandingResponse(
                race.raceId(),
                race.version(),
                race.status(),
                race.timingMode(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    static StandingResponse snapshotStanding(SnapshotRow snapshot, TimingMode timingMode) {
        Map<String, SnapshotIntervalRow> finishByBib = finishIntervalsByBib(snapshot);
        return new StandingResponse(
                snapshot.raceId(),
                snapshot.version(),
                RaceStatus.SEALED,
                timingMode,
                snapshot.sealedAt(),
                snapshot.entries().stream()
                        .map(entry -> toEntryResponse(entry, finishByBib.get(entry.bib())))
                        .toList());
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
        return toEntryResponse(entry, null);
    }

    /** 区间模式快照条目：原始完赛区间来自固化的 FINISH 区间明细。 */
    static ResultEntryResponse toEntryResponse(
            SnapshotEntryRow entry, SnapshotIntervalRow finishInterval) {
        Long finishLower = finishInterval == null ? null : finishInterval.lowerMs();
        Long finishUpper = finishInterval == null ? null : finishInterval.upperMs();
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
                entry.rankBest(),
                entry.rankWorst(),
                finishLower,
                finishUpper,
                entry.netLowerMs(),
                entry.netUpperMs());
    }

    static ResultEntryResponse toEntryResponse(IntervalResultEntry entry) {
        return new ResultEntryResponse(
                entry.bib(),
                null,
                entry.status(),
                null,
                entry.penaltyMs(),
                null,
                entry.checkpointCount(),
                entry.coveredCheckpointCount(),
                entry.missingCheckpoints(),
                entry.rankBest(),
                entry.rankWorst(),
                entry.finishLowerMs(),
                entry.finishUpperMs(),
                entry.netLowerMs(),
                entry.netUpperMs());
    }

    static IntervalTimingResponse toIntervalResponse(IntervalTimingRow row) {
        return new IntervalTimingResponse(
                row.timingId(), row.bib(), row.checkpointCode(), row.position(),
                row.lowerMs(), row.upperMs(), row.operator(), row.createdAt(), row.updatedAt());
    }

    /** 提取封榜快照中每名选手的完赛区间（计时点代码 FINISH）。 */
    static Map<String, SnapshotIntervalRow> finishIntervalsByBib(SnapshotRow snapshot) {
        Map<String, SnapshotIntervalRow> byBib = new LinkedHashMap<>();
        for (SnapshotIntervalRow interval : snapshot.intervals()) {
            if (IntervalRules.FINISH_CODE.equals(interval.checkpointCode())) {
                byBib.put(interval.bib(), interval);
            }
        }
        return byBib;
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
     * 组装单个选手的区间明细：按计时点顺序（检查点在前、完赛点 FINISH 最后），
     * 无记录的计时点 lowerMs/upperMs/timingId 为 null。
     */
    static RunnerIntervalsResponse runnerIntervals(
            RaceRow race,
            RunnerRow runner,
            List<CheckpointRow> checkpoints,
            List<IntervalTimingRow> intervals) {
        Map<String, IntervalTimingRow> byCode = new LinkedHashMap<>();
        for (IntervalTimingRow interval : intervals) {
            byCode.put(interval.checkpointCode(), interval);
        }
        List<IntervalPassResponse> points = new java.util.ArrayList<>();
        for (CheckpointRow checkpoint : checkpoints) {
            IntervalTimingRow interval = byCode.get(checkpoint.checkpointCode());
            points.add(new IntervalPassResponse(
                    checkpoint.checkpointCode(),
                    checkpoint.position(),
                    interval == null ? null : interval.lowerMs(),
                    interval == null ? null : interval.upperMs(),
                    interval == null ? null : interval.timingId()));
        }
        IntervalTimingRow finish = byCode.get(IntervalRules.FINISH_CODE);
        points.add(new IntervalPassResponse(
                IntervalRules.FINISH_CODE,
                checkpoints.size() + 1,
                finish == null ? null : finish.lowerMs(),
                finish == null ? null : finish.upperMs(),
                finish == null ? null : finish.timingId()));
        return new RunnerIntervalsResponse(
                race.raceId(), runner.bib(), race.version(), points);
    }

    /** 由封榜快照中的区间明细组装单选手区间视图（无记录的计时点字段为 null）。 */
    static RunnerIntervalsResponse snapshotRunnerIntervals(
            SnapshotRow snapshot,
            String bib,
            List<CheckpointRow> checkpoints) {
        Map<String, SnapshotIntervalRow> byCode = new LinkedHashMap<>();
        for (SnapshotIntervalRow detail : snapshot.intervals()) {
            if (detail.bib().equals(bib)) {
                byCode.put(detail.checkpointCode(), detail);
            }
        }
        List<IntervalPassResponse> points = new java.util.ArrayList<>();
        for (CheckpointRow checkpoint : checkpoints) {
            SnapshotIntervalRow detail = byCode.get(checkpoint.checkpointCode());
            points.add(new IntervalPassResponse(
                    checkpoint.checkpointCode(),
                    checkpoint.position(),
                    detail == null ? null : detail.lowerMs(),
                    detail == null ? null : detail.upperMs(),
                    detail == null ? null : detail.timingId()));
        }
        SnapshotIntervalRow finish = byCode.get(IntervalRules.FINISH_CODE);
        points.add(new IntervalPassResponse(
                IntervalRules.FINISH_CODE,
                checkpoints.size() + 1,
                finish == null ? null : finish.lowerMs(),
                finish == null ? null : finish.upperMs(),
                finish == null ? null : finish.timingId()));
        return new RunnerIntervalsResponse(
                snapshot.raceId(), bib, snapshot.version(), points);
    }
}
