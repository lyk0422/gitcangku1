package com.example.starter.race.service;

import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.ConfigureStagesRequest;
import com.example.starter.race.api.ReviseStageTimingRequest;
import com.example.starter.race.api.StageResultsResponse;
import com.example.starter.race.api.StageTimingResponse;
import com.example.starter.race.api.StagesConfigResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitStageTimingRequest;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.StageResult;
import com.example.starter.race.domain.StageResultCalculator;
import com.example.starter.race.domain.StageTimingRules;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRepository;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotStageCheckpointRow;
import com.example.starter.race.persistence.SnapshotStageEntryRow;
import com.example.starter.race.persistence.SnapshotStageRow;
import com.example.starter.race.persistence.StageCheckpointRow;
import com.example.starter.race.persistence.StageRow;
import com.example.starter.race.persistence.StageTimingRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@link StageService} 的事务实现；并发与一致性约定与 {@link RaceServiceImpl} 相同：
 * race 行写锁串行化同赛事写操作，条件 UPDATE 推进版本，幂等记录与业务原子提交。
 */
@Service
public class StageServiceImpl implements StageService {

    /** 阶段数量下界（含）。 */
    private static final int MIN_STAGES = 2;
    /** 阶段数量上界（含）。 */
    private static final int MAX_STAGES = 5;
    /** 每阶段检查点数量上界（含）。 */
    private static final int MAX_CHECKPOINTS_PER_STAGE = 20;

    private final RaceRepository repository;
    private final Clock clock;
    private final IdempotencyExecutor idempotency;

    public StageServiceImpl(RaceRepository repository, Clock clock, IdempotencyExecutor idempotency) {
        this.repository = repository;
        this.clock = clock;
        this.idempotency = idempotency;
    }

    @Override
    @Transactional
    public ServiceResult configureStages(String raceId, ConfigureStagesRequest request) {
        List<ConfigureStagesRequest.StageDefinition> definitions = request.stages().stream()
                .sorted(Comparator.comparingInt(ConfigureStagesRequest.StageDefinition::stageNo))
                .toList();
        return idempotency.execute(request.requestId(), "CONFIGURE_STAGES",
                IdempotencyExecutor.orderedParams(
                        "raceId", raceId,
                        "operator", request.operator(),
                        "expectedVersion", request.expectedVersion(),
                        "stages", definitions.stream()
                                .map(def -> def.stageNo() + "=" + sortedCheckpointKeys(def.checkpoints()))
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    validateStageDefinitions(definitions);
                    if (repository.isStagesRace(raceId)) {
                        throw new ConflictException("赛事已配置阶段，配置后不可修改: " + raceId);
                    }
                    if (!repository.findCheckpoints(raceId).isEmpty()) {
                        throw new ConflictException("赛事已配置单日检查点，不能再配置阶段: " + raceId);
                    }
                    if (repository.countTimings(raceId) > 0 || repository.countStageTimings(raceId) > 0) {
                        throw new ConflictException("赛事已存在计时数据，不能再配置阶段: " + raceId);
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    repository.insertStages(definitions.stream()
                            .map(def -> new StageRow(raceId, def.stageNo(), now))
                            .toList());
                    List<StageCheckpointRow> checkpointRows = new ArrayList<>();
                    List<StagesConfigResponse.StageConfig> stageConfigs = new ArrayList<>();
                    for (ConfigureStagesRequest.StageDefinition def : definitions) {
                        List<ConfigureStagesRequest.CheckpointDefinition> checkpoints =
                                sortedCheckpoints(def.checkpoints());
                        for (ConfigureStagesRequest.CheckpointDefinition cp : checkpoints) {
                            checkpointRows.add(new StageCheckpointRow(
                                    raceId, def.stageNo(), cp.checkpointCode(), cp.position(), now));
                        }
                        stageConfigs.add(new StagesConfigResponse.StageConfig(
                                def.stageNo(),
                                checkpoints.stream()
                                        .map(cp -> new CheckpointResponse(
                                                cp.checkpointCode(), cp.position()))
                                        .toList()));
                    }
                    repository.insertStageCheckpoints(checkpointRows);
                    return ServiceResult.created(new StagesConfigResponse(
                            raceId, request.expectedVersion() + 1, stageConfigs));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitStageTiming(
            String raceId, int stageNo, String bib, SubmitStageTimingRequest request) {
        return idempotency.execute(request.requestId(), "SUBMIT_STAGE_TIMING",
                IdempotencyExecutor.orderedParams(
                        "raceId", raceId,
                        "stageNo", stageNo,
                        "bib", bib,
                        "operator", request.operator(),
                        "timingId", request.timingId(),
                        "checkpointCode", request.checkpointCode(),
                        "elapsedMillis", request.elapsedMillis(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // timingId 是第二层全局幂等键：同参重放原结果，异参409。
                    StageTimingRow existing = repository.findStageTiming(request.timingId()).orElse(null);
                    if (existing != null) {
                        return replayTimingOrConflict(raceId, stageNo, bib, request, existing);
                    }
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireStagesRace(raceId);
                    requireStage(raceId, stageNo);
                    requireRunner(raceId, bib);
                    StageCheckpointRow checkpoint = repository
                            .findStageCheckpoint(raceId, stageNo, request.checkpointCode())
                            .orElseThrow(() -> new NotFoundException(
                                    "阶段检查点不存在: " + request.checkpointCode()));
                    List<StageTimingRow> runnerTimings =
                            repository.findStageTimingsForRunner(raceId, stageNo, bib);
                    String violation = StageTimingRules.validateSubmit(
                            runnerTimings, checkpoint.position(), request.elapsedMillis());
                    if (violation != null) {
                        throw new UnprocessableEntityException(violation);
                    }
                    bumpVersion(race, request.expectedVersion());
                    StageTimingRow row = new StageTimingRow(
                            request.timingId(), raceId, stageNo, bib, request.checkpointCode(),
                            checkpoint.position(), request.elapsedMillis(), clock.millis());
                    try {
                        repository.insertStageTiming(row);
                    } catch (DuplicateKeyException ex) {
                        // 并发下唯一约束兜底：同 timingId 走重放/异参冲突，其余为分段重复。
                        if (constraintMatches(ex, "pk_stage_timing")) {
                            StageTimingRow concurrent =
                                    repository.findStageTiming(request.timingId()).orElseThrow();
                            return replayTimingOrConflict(raceId, stageNo, bib, request, concurrent);
                        }
                        throw new UnprocessableEntityException(
                                "同一选手同一阶段同一检查点最多一条分段记录");
                    }
                    StageTimingRow saved = repository.findStageTiming(request.timingId()).orElseThrow();
                    return ServiceResult.created(toTimingResponse(saved));
                });
    }

    private static List<ConfigureStagesRequest.CheckpointDefinition> sortedCheckpoints(
            List<ConfigureStagesRequest.CheckpointDefinition> checkpoints) {
        return checkpoints.stream()
                .sorted(Comparator
                        .comparingInt(ConfigureStagesRequest.CheckpointDefinition::position)
                        .thenComparing(ConfigureStagesRequest.CheckpointDefinition::checkpointCode))
                .toList();
    }

    private static List<String> sortedCheckpointKeys(
            List<ConfigureStagesRequest.CheckpointDefinition> checkpoints) {
        return sortedCheckpoints(checkpoints).stream()
                .map(cp -> cp.position() + ":" + cp.checkpointCode())
                .toList();
    }

    @Override
    @Transactional
    public ServiceResult reviseStageTiming(
            String raceId, int stageNo, String timingId, ReviseStageTimingRequest request) {
        return idempotency.execute(request.requestId(), "REVISE_STAGE_TIMING",
                IdempotencyExecutor.orderedParams(
                        "raceId", raceId,
                        "stageNo", stageNo,
                        "timingId", timingId,
                        "operator", request.operator(),
                        "elapsedMillis", request.elapsedMillis(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireStagesRace(raceId);
                    StageTimingRow existing = repository.findStageTiming(timingId)
                            .orElseThrow(() -> new NotFoundException("阶段分段记录不存在: " + timingId));
                    if (!existing.raceId().equals(raceId) || existing.stageNo() != stageNo) {
                        throw new NotFoundException("阶段分段记录不属于该赛事或阶段: " + timingId);
                    }
                    List<StageTimingRow> runnerTimings = repository.findStageTimingsForRunner(
                            raceId, stageNo, existing.bib());
                    String violation = StageTimingRules.validateRevision(
                            runnerTimings, timingId, existing.position(), request.elapsedMillis());
                    if (violation != null) {
                        throw new UnprocessableEntityException(violation);
                    }
                    bumpVersion(race, request.expectedVersion());
                    repository.updateStageTiming(timingId, request.elapsedMillis());
                    StageTimingRow saved = repository.findStageTiming(timingId).orElseThrow();
                    return ServiceResult.ok(toTimingResponse(saved));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public StageResultsResponse getStageResults(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            SnapshotRow snapshot = repository.findSnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少快照: " + raceId));
            Map<Integer, List<SnapshotStageEntryRow>> entriesByStage = new TreeMap<>();
            for (SnapshotStageEntryRow entry : repository.findSnapshotStageEntries(raceId)) {
                entriesByStage.computeIfAbsent(entry.stageNo(), key -> new ArrayList<>()).add(entry);
            }
            List<StageResultsResponse.StageResultView> stages =
                    repository.findSnapshotStages(raceId).stream()
                            .map(stage -> new StageResultsResponse.StageResultView(
                                    stage.stageNo(),
                                    stage.fastestMs(),
                                    entriesByStage.getOrDefault(stage.stageNo(), List.of())
                                            .stream()
                                            .map(entry -> new StageResultsResponse.StageEntryView(
                                                    entry.bib(), entry.status(), entry.rawTimeMs(),
                                                    entry.checkpointCount(),
                                                    entry.coveredCheckpointCount()))
                                            .toList()))
                            .toList();
            return new StageResultsResponse(
                    raceId, snapshot.version(), RaceStatus.SEALED, snapshot.sealedAt(), stages);
        }
        requireStagesRace(raceId);
        List<StageResult> results = computeStageResults(raceId);
        return new StageResultsResponse(
                raceId, race.version(), race.status(), null, toStageViews(results));
    }

    @Override
    @Transactional
    public ServiceResult sealStages(RaceRow race, int expectedVersion) {
        String raceId = race.raceId();
        List<RunnerRow> runners = repository.findRunners(raceId);
        List<PenaltyRow> penalties = repository.findPenalties(raceId);
        List<StageCheckpointRow> checkpoints = repository.findStageCheckpoints(raceId);
        List<StageTimingRow> timings = repository.findAllStageTimings(raceId);
        List<String> bibs = runners.stream().map(RunnerRow::bib).toList();
        List<StageResult> stageResults =
                StageResultCalculator.computeStages(bibs, checkpoints, timings);
        for (StageResult stageResult : stageResults) {
            if (stageResult.fastestMs() == null) {
                throw new UnprocessableEntityException(
                        "阶段无完整选手，不能封榜: stageNo=" + stageResult.stageNo());
            }
        }
        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                bibs, penalties, checkpoints, timings, stageResults);

        int newVersion = expectedVersion + 1;
        int updated = repository.sealIfOpenAtVersion(raceId, expectedVersion, newVersion);
        if (updated == 0) {
            throw new ConflictException("版本冲突或赛事已封榜");
        }
        long now = clock.millis();
        List<SnapshotEntryRow> snapshotEntries = new ArrayList<>(overall.size());
        for (int order = 0; order < overall.size(); order++) {
            ResultEntry entry = overall.get(order);
            snapshotEntries.add(new SnapshotEntryRow(
                    raceId,
                    entry.bib(),
                    entry.rank(),
                    entry.status(),
                    entry.finishTimeMs(),
                    entry.penaltyMs(),
                    entry.totalTimeMs(),
                    order,
                    entry.checkpointCount(),
                    entry.coveredCheckpointCount(),
                    entry.missingCheckpoints()));
        }
        // 单日分段明细表对阶段赛事恒为空；阶段原始点固化到阶段快照明细表。
        repository.insertSnapshot(new SnapshotRow(raceId, newVersion, now,
                snapshotEntries, List.of()));
        List<SnapshotStageRow> stageRows = stageResults.stream()
                .map(result -> new SnapshotStageRow(
                        raceId, result.stageNo(), result.fastestMs(), result.completeCount()))
                .toList();
        List<SnapshotStageEntryRow> stageEntryRows = new ArrayList<>();
        for (StageResult result : stageResults) {
            for (com.example.starter.race.domain.StageEntry entry : result.entries()) {
                stageEntryRows.add(new SnapshotStageEntryRow(
                        raceId, result.stageNo(), entry.bib(), entry.rawTimeMs(),
                        entry.checkpointCount(), entry.coveredCheckpointCount(), entry.status()));
            }
        }
        repository.insertStageSnapshot(stageRows, stageEntryRows,
                buildStageSnapshotCheckpoints(raceId, runners, checkpoints, timings));
        return ServiceResult.ok(new StandingResponse(
                raceId, newVersion, RaceStatus.SEALED, now,
                snapshotEntries.stream().map(ResponseMapper::toEntryResponse).toList()));
    }

    @Override
    @Transactional(readOnly = true)
    public StandingResponse liveOverallStanding(RaceRow race) {
        String raceId = race.raceId();
        List<RunnerRow> runners = repository.findRunners(raceId);
        List<ResultEntry> overall = StageResultCalculator.computeOverall(
                runners.stream().map(RunnerRow::bib).toList(),
                repository.findPenalties(raceId),
                repository.findStageCheckpoints(raceId),
                repository.findAllStageTimings(raceId),
                computeStageResults(raceId));
        return new StandingResponse(
                race.raceId(), race.version(), race.status(), null,
                overall.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /** 由当前库内数据重算全部阶段结果（不缓存、不沿用旧最快值）。 */
    private List<StageResult> computeStageResults(String raceId) {
        List<String> bibs = repository.findRunners(raceId).stream()
                .map(RunnerRow::bib)
                .toList();
        return StageResultCalculator.computeStages(
                bibs,
                repository.findStageCheckpoints(raceId),
                repository.findAllStageTimings(raceId));
    }

    private static List<StageResultsResponse.StageResultView> toStageViews(
            List<StageResult> results) {
        return results.stream()
                .map(result -> new StageResultsResponse.StageResultView(
                        result.stageNo(),
                        result.fastestMs(),
                        result.entries().stream()
                                .map(entry -> new StageResultsResponse.StageEntryView(
                                        entry.bib(), entry.status(), entry.rawTimeMs(),
                                        entry.checkpointCount(),
                                        entry.coveredCheckpointCount()))
                                .toList()))
                .toList();
    }

    /**
     * 构造阶段封榜快照的全部原始点明细：每名选手 × 每阶段每个检查点一行，
     * 已有通过记录固化耗时与 timingId，缺失检查点对应字段为 null。
     */
    private List<SnapshotStageCheckpointRow> buildStageSnapshotCheckpoints(
            String raceId,
            List<RunnerRow> runners,
            List<StageCheckpointRow> checkpoints,
            List<StageTimingRow> timings) {
        Map<String, StageTimingRow> timingByKey = new TreeMap<>();
        for (StageTimingRow timing : timings) {
            timingByKey.put(timing.stageNo() + "|" + timing.bib() + "|" + timing.checkpointCode(),
                    timing);
        }
        List<SnapshotStageCheckpointRow> rows = new ArrayList<>();
        for (RunnerRow runner : runners) {
            for (StageCheckpointRow checkpoint : checkpoints) {
                StageTimingRow timing = timingByKey.get(
                        checkpoint.stageNo() + "|" + runner.bib() + "|"
                                + checkpoint.checkpointCode());
                if (timing == null) {
                    rows.add(new SnapshotStageCheckpointRow(
                            raceId, checkpoint.stageNo(), runner.bib(),
                            checkpoint.checkpointCode(), checkpoint.position(), null, null));
                } else {
                    rows.add(new SnapshotStageCheckpointRow(
                            raceId, checkpoint.stageNo(), runner.bib(),
                            checkpoint.checkpointCode(), checkpoint.position(),
                            timing.elapsedMillis(), timing.timingId()));
                }
            }
        }
        return rows;
    }

    private ServiceResult replayTimingOrConflict(
            String raceId,
            int stageNo,
            String bib,
            SubmitStageTimingRequest request,
            StageTimingRow existing) {
        boolean sameParams = existing.raceId().equals(raceId)
                && existing.stageNo() == stageNo
                && existing.bib().equals(bib)
                && existing.checkpointCode().equals(request.checkpointCode())
                && existing.elapsedMillis() == request.elapsedMillis();
        if (!sameParams) {
            throw new ConflictException(
                    "timingId 已用于不同参数的阶段分段记录: " + request.timingId());
        }
        return ServiceResult.created(toTimingResponse(existing));
    }

    private static StageTimingResponse toTimingResponse(StageTimingRow row) {
        return new StageTimingResponse(
                row.timingId(), row.stageNo(), row.bib(), row.checkpointCode(),
                row.position(), row.elapsedMillis(), row.createdAt());
    }

    private RaceRow requireOpenRace(String raceId, Integer expectedVersion) {
        // 与单日写路径一致：先取 race 行写锁并持有到事务结束，串行化同赛事并发写。
        RaceRow race = repository.findRaceForUpdate(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，禁止写入: " + raceId);
        }
        if (race.version() != expectedVersion) {
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + race.version());
        }
        return race;
    }

    private void requireStagesRace(String raceId) {
        if (!repository.isStagesRace(raceId)) {
            throw new ConflictException("赛事未配置阶段，禁止使用阶段入口: " + raceId);
        }
    }

    private void requireStage(String raceId, int stageNo) {
        boolean exists = repository.findStages(raceId).stream()
                .anyMatch(stage -> stage.stageNo() == stageNo);
        if (!exists) {
            throw new NotFoundException("阶段不存在: stageNo=" + stageNo);
        }
    }

    private void requireRunner(String raceId, String bib) {
        repository.findRunner(raceId, bib)
                .orElseThrow(() -> new NotFoundException("选手不存在: " + bib));
    }

    private void bumpVersion(RaceRow race, int expectedVersion) {
        int updated = repository.bumpVersionIfOpen(race.raceId(), expectedVersion);
        if (updated == 0) {
            RaceRow refreshed = repository.findRace(race.raceId()).orElseThrow();
            if (refreshed.status() == RaceStatus.SEALED) {
                throw new ConflictException("赛事已封榜，禁止写入: " + race.raceId());
            }
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + refreshed.version());
        }
    }

    /** 校验阶段配置请求体：2~5个阶段、stageNo 从1连续递增；每阶段1~20个检查点、代码阶段内唯一、position 从1连续递增。 */
    private static void validateStageDefinitions(
            List<ConfigureStagesRequest.StageDefinition> definitions) {
        if (definitions.size() < MIN_STAGES || definitions.size() > MAX_STAGES) {
            throw new BadRequestException("阶段数量必须在 2~5 之间");
        }
        for (int index = 0; index < definitions.size(); index++) {
            ConfigureStagesRequest.StageDefinition def = definitions.get(index);
            if (def.stageNo() != index + 1) {
                throw new BadRequestException(
                        "阶段序号必须从1连续递增，缺少 stageNo=" + (index + 1));
            }
            List<ConfigureStagesRequest.CheckpointDefinition> checkpoints = def.checkpoints();
            if (checkpoints.isEmpty() || checkpoints.size() > MAX_CHECKPOINTS_PER_STAGE) {
                throw new BadRequestException("每阶段检查点数量必须在 1~20 之间");
            }
            Set<String> codes = new HashSet<>();
            Set<Integer> positions = new HashSet<>();
            for (ConfigureStagesRequest.CheckpointDefinition cp : checkpoints) {
                if (!codes.add(cp.checkpointCode())) {
                    throw new BadRequestException(
                            "检查点代码在阶段内重复: " + cp.checkpointCode());
                }
                positions.add(cp.position());
            }
            for (int expected = 1; expected <= checkpoints.size(); expected++) {
                if (!positions.contains(expected)) {
                    throw new BadRequestException(
                            "阶段内检查点顺序必须从1连续递增，缺少 position=" + expected);
                }
            }
        }
    }

    /** 判断唯一约束异常是否源自指定约束（H2 异常信息中的约束名可能为大写）。 */
    private static boolean constraintMatches(DuplicateKeyException ex, String constraintName) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null
                && message.toUpperCase(java.util.Locale.ROOT)
                        .contains(constraintName.toUpperCase(java.util.Locale.ROOT));
    }
}
