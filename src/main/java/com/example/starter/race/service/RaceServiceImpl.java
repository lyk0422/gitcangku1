package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.CompensationDetailResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeRaceRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerCompensationResponse;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SuspendRaceRequest;
import com.example.starter.race.api.SuspensionHistoryResponse;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.NetTimingCalculator;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.SuspensionStatus;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotSuspensionRow;
import com.example.starter.race.persistence.SuspensionEventRow;
import com.example.starter.race.persistence.RaceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * {@link RaceService} 的事务实现。
 *
 * <p>并发与一致性要点：
 * <ul>
 *   <li>所有写操作先在 race 行上执行“状态=OPEN 且 version=expectedVersion”的条件 UPDATE，
 *       行锁串行化同赛事并发写，版本不匹配/已封榜均为0行并返回409，因此不会遗漏已先提交的变更；</li>
 *   <li>封榜在同一事务内完成条件转 SEALED 与全体选手只读快照写入；</li>
 *   <li>每个写请求以 requestId 唯一键先插入“进行中”占位行：同键并发阻塞至先行者事务结束；
 *       成功后在同一事务内补写原响应；业务失败随事务回滚，不占用 requestId 键；
 *       同键同参重放原成功结果，异参返回409。</li>
 * </ul>
 */
@Service
public class RaceServiceImpl implements RaceService {

    /** 原始完赛耗时上界（毫秒），含端点：1天。 */
    private static final long MAX_FINISH_TIME_MS = 86_400_000L;
    /** 加时处罚上界（毫秒），含端点：1小时。 */
    private static final long MAX_PENALTY_MS = 3_600_000L;
    /** 同键并发时等待先行者事务结束的上限（毫秒）。 */
    private static final long INFLIGHT_WAIT_MAX_MS = 30_000L;

    private final RaceRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public RaceServiceImpl(RaceRepository repository, Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ServiceResult createRace(CreateRaceRequest request) {
        return withIdempotency(request.requestId(), "CREATE_RACE",
                orderedParams("raceId", request.raceId()),
                () -> {
                    long now = clock.millis();
                    try {
                        repository.insertRace(request.raceId(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("赛事已存在: " + request.raceId());
                    }
                    RaceRow race = repository.findRace(request.raceId()).orElseThrow();
                    return ServiceResult.created(new RaceResponse(
                            race.raceId(), race.version(), race.status(), race.createdAt()));
                });
    }

    @Override
    @Transactional
    public ServiceResult registerRunner(String raceId, RegisterRunnerRequest request) {
        return withIdempotency(request.requestId(), "REGISTER_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", request.bib(),
                        "expectedVersion", request.expectedVersion(),
                        "finishTimeMs", request.finishTimeMs()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    Long finishTimeMs = request.finishTimeMs();
                    validateFinishTime(finishTimeMs, true);
                    rejectIfInSuspensionWindow(raceId, finishTimeMs);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertRunner(raceId, request.bib(), finishTimeMs, now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("参赛号已存在: " + request.bib());
                    }
                    RunnerRow runner = repository.findRunner(raceId, request.bib()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toRunnerResponse(runner));
                });
    }

    @Override
    @Transactional
    public ServiceResult reviseTime(String raceId, ReviseTimeRequest request) {
        return withIdempotency(request.requestId(), "REVISE_TIME",
                orderedParams(
                        "raceId", raceId,
                        "bib", request.bib(),
                        "expectedVersion", request.expectedVersion(),
                        "finishTimeMs", request.finishTimeMs()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, request.bib());
                    validateFinishTime(request.finishTimeMs(), false);
                    rejectIfInSuspensionWindow(raceId, request.finishTimeMs());
                    // 修订后原始完赛耗时仍须严格大于该选手每一条已有分段耗时，否则分段不变量被破坏。
                    List<CheckpointTimingRow> runnerTimings =
                            repository.findTimingsForRunner(raceId, request.bib());
                    for (CheckpointTimingRow timing : runnerTimings) {
                        if (timing.elapsedMillis() >= request.finishTimeMs()) {
                            throw new UnprocessableEntityException(
                                    "修订后的完赛耗时必须严格大于已有分段耗时: "
                                            + timing.checkpointCode());
                        }
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.updateRunnerTiming(
                            raceId, request.bib(), request.finishTimeMs(), now);
                    if (updated == 0) {
                        throw new NotFoundException("选手不存在: " + request.bib());
                    }
                    RunnerRow refreshedRunner =
                            repository.findRunner(raceId, request.bib()).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toRunnerResponse(refreshedRunner));
                });
    }

    @Override
    @Transactional
    public ServiceResult addPenalty(String raceId, AddPenaltyRequest request) {
        return withIdempotency(request.requestId(), "ADD_PENALTY",
                orderedParams(
                        "raceId", raceId,
                        "penaltyId", request.penaltyId(),
                        "bib", request.bib(),
                        "type", request.type(),
                        "amountMs", request.amountMs(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, request.bib());
                    PenaltyType type = parsePenaltyType(request.type());
                    Long amountMs = request.amountMs();
                    if (type == PenaltyType.ADD_TIME) {
                        if (amountMs == null) {
                            throw new BadRequestException("加时处罚必须携带 amountMs");
                        }
                        if (amountMs < 1 || amountMs > MAX_PENALTY_MS) {
                            throw new BadRequestException("加时毫秒数必须在 1~3600000 之间");
                        }
                    } else if (amountMs != null) {
                        throw new BadRequestException("取消资格处罚不得携带 amountMs");
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertPenalty(
                                request.penaltyId(), raceId, request.bib(), type, amountMs, now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("处罚ID已存在: " + request.penaltyId());
                    }
                    PenaltyRow penalty = repository.findPenalty(request.penaltyId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toPenaltyResponse(penalty));
                });
    }

    @Override
    @Transactional
    public ServiceResult revokePenalty(
            String raceId, String penaltyId, RevokePenaltyRequest request) {
        return withIdempotency(request.requestId(), "REVOKE_PENALTY",
                orderedParams(
                        "raceId", raceId,
                        "penaltyId", penaltyId,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    PenaltyRow penalty = repository.findPenalty(penaltyId)
                            .orElseThrow(() -> new NotFoundException("处罚不存在: " + penaltyId));
                    if (!penalty.raceId().equals(raceId)) {
                        throw new NotFoundException("处罚不属于该赛事: " + penaltyId);
                    }
                    if (penalty.revoked()) {
                        throw new ConflictException("处罚已撤销: " + penaltyId);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.markPenaltyRevoked(penaltyId, now);
                    if (updated == 0) {
                        throw new ConflictException("处罚已撤销: " + penaltyId);
                    }
                    PenaltyRow refreshed = repository.findPenalty(penaltyId).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toPenaltyResponse(refreshed));
                });
    }

    @Override
    @Transactional
    public ServiceResult configureCheckpoints(String raceId, ConfigureCheckpointsRequest request) {
        List<ConfigureCheckpointsRequest.CheckpointDefinition> definitions =
                request.checkpoints().stream()
                        .sorted(java.util.Comparator
                                .comparingInt(ConfigureCheckpointsRequest.CheckpointDefinition::position)
                                .thenComparing(ConfigureCheckpointsRequest.CheckpointDefinition::checkpointCode))
                        .toList();
        return withIdempotency(request.requestId(), "CONFIGURE_CHECKPOINTS",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion(),
                        "checkpoints", definitions.stream()
                                .map(def -> def.position() + ":" + def.checkpointCode())
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    validateCheckpointDefinitions(request.checkpoints());
                    if (!repository.findCheckpoints(raceId).isEmpty()) {
                        throw new ConflictException("赛事已配置检查点，配置后不可修改: " + raceId);
                    }
                    if (repository.countTimings(raceId) > 0) {
                        throw new ConflictException("赛事已存在分段记录，不能再配置检查点: " + raceId);
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    List<CheckpointRow> rows = request.checkpoints().stream()
                            .map(def -> new CheckpointRow(
                                    raceId, def.checkpointCode(), def.position(), now))
                            .toList();
                    repository.insertCheckpoints(rows);
                    List<CheckpointResponse> responses = repository.findCheckpoints(raceId).stream()
                            .map(row -> new CheckpointResponse(row.checkpointCode(), row.position()))
                            .toList();
                    return ServiceResult.created(new CheckpointsConfigResponse(
                            raceId, request.expectedVersion() + 1, responses));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitTiming(String raceId, String bib, SubmitTimingRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_TIMING",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "timingId", request.timingId(),
                        "checkpointCode", request.checkpointCode(),
                        "elapsedMillis", request.elapsedMillis(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // timingId 是第二层全局幂等键：同参重放原结果，异参409。
                    CheckpointTimingRow existingTiming =
                            repository.findTiming(request.timingId()).orElse(null);
                    if (existingTiming != null) {
                        return replayTimingOrConflict(raceId, bib, request, existingTiming);
                    }

                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    CheckpointRow checkpoint = repository
                            .findCheckpoint(raceId, request.checkpointCode())
                            .orElseThrow(() -> new NotFoundException(
                                    "检查点不存在: " + request.checkpointCode()));
                    long elapsedMillis = request.elapsedMillis();
                    List<CheckpointTimingRow> runnerTimings =
                            repository.findTimingsForRunner(raceId, bib);
                    String violation = CheckpointRules.validate(
                            runnerTimings, checkpoint.position(), elapsedMillis,
                            runner.finishTimeMs());
                    if (violation != null) {
                        throw new UnprocessableEntityException(violation);
                    }
                    rejectIfInSuspensionWindow(raceId, elapsedMillis);
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    CheckpointTimingRow row = new CheckpointTimingRow(
                            request.timingId(), raceId, bib, request.checkpointCode(),
                            checkpoint.position(), elapsedMillis, now);
                    try {
                        repository.insertTiming(row);
                    } catch (DuplicateKeyException ex) {
                        // 并发下唯一约束兜底：同 timingId 走重放/异参冲突，其余为分段重复。
                        if (constraintMatches(ex, "pk_checkpoint_timing")) {
                            CheckpointTimingRow concurrent =
                                    repository.findTiming(request.timingId()).orElseThrow();
                            return replayTimingOrConflict(raceId, bib, request, concurrent);
                        }
                        throw new UnprocessableEntityException(
                                "同一选手同一检查点最多一条分段记录");
                    }
                    CheckpointTimingRow saved =
                            repository.findTiming(request.timingId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toTimingResponse(saved));
                });
    }

    private ServiceResult replayTimingOrConflict(
            String raceId,
            String bib,
            SubmitTimingRequest request,
            CheckpointTimingRow existing) {
        boolean sameParams = existing.raceId().equals(raceId)
                && existing.bib().equals(bib)
                && existing.checkpointCode().equals(request.checkpointCode())
                && existing.elapsedMillis() == request.elapsedMillis();
        if (!sameParams) {
            throw new ConflictException(
                    "timingId 已用于不同参数的分段记录: " + request.timingId());
        }
        return ServiceResult.created(ResponseMapper.toTimingResponse(existing));
    }

    @Override
    @Transactional
    public ServiceResult sealRace(String raceId, SealRaceRequest request) {
        return withIdempotency(request.requestId(), "SEAL_RACE",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = repository.findRaceForUpdate(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() == RaceStatus.SEALED) {
                        throw new ConflictException("赛事已封榜: " + raceId);
                    }
                    if (race.status() == RaceStatus.SUSPENDED) {
                        throw new ConflictException("赛事处于中止状态，不能封榜: " + raceId);
                    }
                    List<RunnerRow> runners = repository.findRunners(raceId);
                    List<PenaltyRow> penalties = repository.findPenalties(raceId);
                    List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
                    List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
                    List<SuspensionEventRow> suspensionEvents =
                            repository.findSuspensionEvents(raceId);
                    Map<String, NetTimingCalculator.RunnerNet> netByBib =
                            computeNetForRunners(runners, timings, suspensionEvents);
                    List<ResultEntry> entries = ResultCalculator.compute(
                            netAdjustedViews(runners, netByBib), penalties, checkpoints, timings);

                    int newVersion = request.expectedVersion() + 1;
                    int updated = repository.sealIfOpenAtVersion(
                            raceId, request.expectedVersion(), newVersion);
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事已封榜");
                    }
                    long now = clock.millis();
                    List<SnapshotEntryRow> snapshotEntries = new ArrayList<>(entries.size());
                    for (int order = 0; order < entries.size(); order++) {
                        ResultEntry entry = entries.get(order);
                        snapshotEntries.add(new SnapshotEntryRow(
                                raceId,
                                entry.bib(),
                                entry.rank(),
                                entry.status(),
                                entry.finishTimeMs(),
                                entry.penaltyMs(),
                                entry.totalTimeMs(),
                                entry.netFinishTimeMs(),
                                entry.netTotalTimeMs(),
                                order,
                                entry.checkpointCount(),
                                entry.coveredCheckpointCount(),
                                entry.missingCheckpoints()));
                    }
                    // 在同一封榜事务内固化每名选手 × 每个检查点的明细，缺失行耗时为 null。
                    List<SnapshotCheckpointRow> snapshotCheckpoints =
                            buildSnapshotCheckpoints(raceId, runners, checkpoints, timings, netByBib);
                    // 冻结完整中止事件版本（原始区间），与净值快照同属一个事务。
                    List<SnapshotSuspensionRow> snapshotSuspensions = suspensionEvents.stream()
                            .filter(event -> event.status() == SuspensionStatus.RESUMED)
                            .map(event -> new SnapshotSuspensionRow(
                                    raceId, event.eventKey(), event.checkpointCode(),
                                    event.startElapsedMs(), event.resumeElapsedMs()))
                            .toList();
                    repository.insertSnapshot(new SnapshotRow(raceId, newVersion, now,
                            snapshotEntries, snapshotCheckpoints, snapshotSuspensions));
                    return ServiceResult.ok(new StandingResponse(
                            raceId, newVersion, RaceStatus.SEALED, now,
                            snapshotEntries.stream().map(ResponseMapper::toEntryResponse).toList()));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public StandingResponse getResults(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            SnapshotRow snapshot = repository.findSnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少快照: " + raceId));
            return ResponseMapper.snapshotStanding(snapshot);
        }
        List<RunnerRow> runners = repository.findRunners(raceId);
        List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
        Map<String, NetTimingCalculator.RunnerNet> netByBib = computeNetForRunners(
                runners, timings, repository.findSuspensionEvents(raceId));
        return ResponseMapper.liveStanding(
                race,
                netAdjustedViews(runners, netByBib),
                repository.findPenalties(raceId),
                repository.findCheckpoints(raceId),
                timings);
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerTimingResponse getRunnerTimings(String raceId, String bib) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        RunnerRow runner = repository.findRunner(raceId, bib)
                .orElseThrow(() -> new NotFoundException("选手不存在: " + bib));
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        if (race.status() == RaceStatus.SEALED) {
            SnapshotRow snapshot = repository.findSnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少快照: " + raceId));
            return ResponseMapper.snapshotRunnerTiming(
                    snapshot, bib, runner.finishTimeMs(), checkpoints);
        }
        List<CheckpointTimingRow> timings = repository.findTimingsForRunner(raceId, bib);
        NetTimingCalculator.RunnerNet net = computeNetForRunners(
                List.of(runner), timings, repository.findSuspensionEvents(raceId)).get(bib);
        return ResponseMapper.runnerTiming(
                race, runner, checkpoints, timings,
                net.netElapsedByCheckpoint(), net.netFinishTimeMs());
    }

    @Override
    @Transactional(readOnly = true)
    public MissingCheckpointsResponse getMissingCheckpoints(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        List<RunnerRow> runners;
        List<CheckpointTimingRow> timings;
        if (race.status() == RaceStatus.SEALED) {
            SnapshotRow snapshot = repository.findSnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少快照: " + raceId));
            // 封榜后由固化明细派生缺失检查点，缺失行 elapsedMillis 为 null。
            Map<String, List<String>> missingByBib = new TreeMap<>();
            for (SnapshotCheckpointRow detail : snapshot.checkpoints()) {
                if (detail.elapsedMillis() == null) {
                    missingByBib.computeIfAbsent(detail.bib(), key -> new ArrayList<>())
                            .add(detail.checkpointCode());
                }
            }
            List<RunnerRow> sealedRunners = repository.findRunners(raceId);
            List<RunnerMissingCheckpointsResponse> rows = sealedRunners.stream()
                    .map(runner -> new RunnerMissingCheckpointsResponse(
                            runner.bib(),
                            missingByBib.getOrDefault(runner.bib(), List.of())))
                    .toList();
            return new MissingCheckpointsResponse(
                    raceId, race.version(), checkpoints.size(), rows);
        }
        runners = repository.findRunners(raceId);
        timings = repository.findAllTimings(raceId);
        // 按参赛号字典序稳定汇总；缺失=已配置但该选手尚无分段记录的检查点。
        Map<String, java.util.Set<String>> coveredByBib = new TreeMap<>();
        for (RunnerRow runner : runners) {
            coveredByBib.put(runner.bib(), new java.util.HashSet<>());
        }
        for (CheckpointTimingRow timing : timings) {
            java.util.Set<String> covered = coveredByBib.get(timing.bib());
            if (covered != null) {
                covered.add(timing.checkpointCode());
            }
        }
        List<RunnerMissingCheckpointsResponse> rows = coveredByBib.entrySet().stream()
                .map(entry -> new RunnerMissingCheckpointsResponse(
                        entry.getKey(),
                        checkpoints.stream()
                                .map(CheckpointRow::checkpointCode)
                                .filter(code -> !entry.getValue().contains(code))
                                .toList()))
                .toList();
        return new MissingCheckpointsResponse(
                raceId, race.version(), checkpoints.size(), rows);
    }

    @Override
    @Transactional(readOnly = true)
    public StandingResponse getSnapshot(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        SnapshotRow snapshot = repository.findSnapshot(raceId)
                .orElseThrow(() -> new NotFoundException("赛事尚未封榜: " + raceId));
        return ResponseMapper.snapshotStanding(snapshot);
    }

    /**
     * 幂等包装：同键同参重放原成功结果，同键异参409；
     * 业务异常随事务回滚，占位行消失，不占用 requestId。
     */
    private ServiceResult withIdempotency(
            String requestId,
            String operation,
            TreeMap<String, Object> params,
            Supplier<ServiceResult> action) {
        String digest = digest(operation, params);
        long deadline = clock.millis() + INFLIGHT_WAIT_MAX_MS;
        boolean acquired = false;
        while (!acquired) {
            try {
                repository.insertIdempotencyPlaceholder(
                        requestId, operation, digest, clock.millis());
                acquired = true;
            } catch (DuplicateKeyException ex) {
                Optional<IdempotencyRow> existing =
                        repository.findIdempotencyForUpdate(requestId);
                if (existing.isEmpty()) {
                    // 先行者事务回滚，占位行已随其消失，立即重新占位。
                    continue;
                }
                IdempotencyRow row = existing.get();
                if (row.responseStatus() == 0) {
                    // 理论上不会出现（提交必带最终响应）；防御性等待先行者完成。
                    if (clock.millis() >= deadline) {
                        throw new ConflictException("相同 requestId 的请求仍在处理中");
                    }
                    sleepBriefly();
                    continue;
                }
                if (!row.requestDigest().equals(digest)) {
                    throw new ConflictException(
                            "requestId 已用于不同参数的请求: " + requestId);
                }
                JsonNode replayedBody = parseReplayedBody(row.responseBody());
                return new ServiceResult(replayedBody, row.responseStatus());
            }
        }
        ServiceResult result = action.get();
        repository.completeIdempotency(
                requestId, result.status(), writeJson(result.body()));
        return result;
    }

    private RaceRow requireOpenRace(String raceId, Integer expectedVersion) {
        // 先取 race 行写锁并持有到事务结束：本事务随后读到的选手/处罚/分段数据
        // 必然处于某个已串行化的赛事版本上，不会与并发写者的已提交明细交错。
        RaceRow race = repository.findRaceForUpdate(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，禁止写入: " + raceId);
        }
        if (race.status() == RaceStatus.SUSPENDED) {
            throw new ConflictException("赛事处于中止状态，仅允许恢复: " + raceId);
        }
        if (race.version() != expectedVersion) {
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + race.version());
        }
        return race;
    }

    private void bumpVersion(RaceRow race, int expectedVersion) {
        int updated = repository.bumpVersionIfOpen(race.raceId(), expectedVersion);
        if (updated == 0) {
            // 行锁释放后发现赛事已封榜或版本已被推进。
            RaceRow refreshed = repository.findRace(race.raceId()).orElseThrow();
            if (refreshed.status() == RaceStatus.SEALED) {
                throw new ConflictException("赛事已封榜，禁止写入: " + race.raceId());
            }
            throw new ConflictException("版本冲突: expected=" + expectedVersion
                    + ", actual=" + refreshed.version());
        }
    }

    private RunnerRow requireRunner(String raceId, String bib) {
        return repository.findRunner(raceId, bib)
                .orElseThrow(() -> new NotFoundException("选手不存在: " + bib));
    }

    private static void validateFinishTime(Long finishTimeMs, boolean nullable) {
        if (finishTimeMs == null) {
            if (!nullable) {
                throw new BadRequestException("修订计时必须携带 finishTimeMs");
            }
            return;
        }
        if (finishTimeMs < 1 || finishTimeMs > MAX_FINISH_TIME_MS) {
            throw new BadRequestException("完赛耗时毫秒数必须在 1~86400000 之间");
        }
    }

    private static PenaltyType parsePenaltyType(String type) {
        try {
            return PenaltyType.valueOf(type);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("未知处罚类型: " + type);
        }
    }

    private static TreeMap<String, Object> orderedParams(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须为键值对");
        }
        TreeMap<String, Object> params = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }

    private String digest(String operation, Map<String, Object> params) {
        try {
            Map<String, Object> canonical = new TreeMap<>(params);
            canonical.put("__operation", operation);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(messageDigest.digest(bytes));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("计算请求摘要失败", ex);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化响应失败", ex);
        }
    }

    private JsonNode parseReplayedBody(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("解析原响应失败", ex);
        }
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConflictException("等待同键请求完成时被中断");
        }
    }

    /**
     * 校验检查点配置请求体：1~20个、代码非空且赛事内唯一、position 从1连续递增（无重复无缺口）。
     */
    private static void validateCheckpointDefinitions(
            List<ConfigureCheckpointsRequest.CheckpointDefinition> definitions) {
        if (definitions.isEmpty() || definitions.size() > 20) {
            throw new BadRequestException("检查点数量必须在 1~20 之间");
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        java.util.Set<Integer> positions = new java.util.HashSet<>();
        for (ConfigureCheckpointsRequest.CheckpointDefinition def : definitions) {
            if (!codes.add(def.checkpointCode())) {
                throw new BadRequestException("检查点代码在赛事内重复: " + def.checkpointCode());
            }
            positions.add(def.position());
        }
        for (int expected = 1; expected <= definitions.size(); expected++) {
            if (!positions.contains(expected)) {
                throw new BadRequestException(
                        "检查点顺序必须从1连续递增，缺少 position=" + expected);
            }
        }
    }

    /**
     * 构造封榜快照的全部分段明细：每名选手 × 每个检查点一行，
     * 已有通过记录固化原始/净耗时与 timingId，缺失检查点对应字段为 null；顺序为参赛号、检查点顺序。
     */
    private List<SnapshotCheckpointRow> buildSnapshotCheckpoints(
            String raceId,
            List<RunnerRow> runners,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            Map<String, NetTimingCalculator.RunnerNet> netByBib) {
        Map<String, Map<String, CheckpointTimingRow>> byBibAndCode = new TreeMap<>();
        for (CheckpointTimingRow timing : timings) {
            byBibAndCode
                    .computeIfAbsent(timing.bib(), key -> new TreeMap<>())
                    .put(timing.checkpointCode(), timing);
        }
        List<SnapshotCheckpointRow> rows = new ArrayList<>();
        // runners 已按参赛号字典序返回，保证无分段记录的选手也被完整固化为“全部缺失”。
        for (RunnerRow runner : runners) {
            appendSnapshotCheckpointRows(
                    rows, raceId, runner.bib(), checkpoints,
                    byBibAndCode.getOrDefault(runner.bib(), Map.of()),
                    netByBib.get(runner.bib()).netElapsedByCheckpoint());
        }
        return rows;
    }

    private void appendSnapshotCheckpointRows(
            List<SnapshotCheckpointRow> rows,
            String raceId,
            String bib,
            List<CheckpointRow> checkpoints,
            Map<String, CheckpointTimingRow> timingByCode,
            Map<String, Long> netElapsedByCheckpoint) {
        for (CheckpointRow checkpoint : checkpoints) {
            CheckpointTimingRow timing = timingByCode.get(checkpoint.checkpointCode());
            if (timing == null) {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), null, null, null));
            } else {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), timing.elapsedMillis(),
                        netElapsedByCheckpoint.get(checkpoint.checkpointCode()),
                        timing.timingId()));
            }
        }
    }

    /** 判断唯一约束异常是否源自指定约束（H2 异常信息中的约束名可能为大写）。 */
    private static boolean constraintMatches(DuplicateKeyException ex, String constraintName) {
        Throwable cause = ex.getMostSpecificCause();
        String message = cause.getMessage();
        return message != null
                && message.toUpperCase(java.util.Locale.ROOT).contains(constraintName.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    @Transactional
    public ServiceResult suspendRace(String raceId, SuspendRaceRequest request) {
        return withIdempotency(request.requestId(), "SUSPEND_RACE",
                orderedParams(
                        "raceId", raceId,
                        "eventKey", request.eventKey(),
                        "checkpointKey", request.checkpointKey(),
                        "startElapsedMs", request.startElapsedMs(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    CheckpointRow checkpoint = repository
                            .findCheckpoint(raceId, request.checkpointKey())
                            .orElseThrow(() -> new NotFoundException(
                                    "检查点不存在: " + request.checkpointKey()));
                    long startElapsedMs = request.startElapsedMs();
                    // 中止事件互不重叠：新窗口必须落在全部既有事件之后。
                    for (SuspensionEventRow existing : repository.findSuspensionEvents(raceId)) {
                        if (existing.resumeElapsedMs() == null
                                || existing.resumeElapsedMs() > startElapsedMs
                                || existing.startElapsedMs() >= startElapsedMs) {
                            throw new UnprocessableEntityException(
                                    "中止区间与既有事件重叠: " + existing.eventKey());
                        }
                    }
                    int newVersion = request.expectedVersion() + 1;
                    int updated = repository.suspendRaceIfOpenAtVersion(
                            raceId, request.expectedVersion(), newVersion);
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事非开放状态");
                    }
                    long now = clock.millis();
                    SuspensionEventRow row = new SuspensionEventRow(
                            request.eventKey(), raceId, request.checkpointKey(),
                            checkpoint.position(), startElapsedMs, null,
                            SuspensionStatus.SUSPENDED, now, null);
                    try {
                        repository.insertSuspensionEvent(row);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("eventKey已存在: " + request.eventKey());
                    }
                    SuspensionEventRow saved =
                            repository.findSuspensionEvent(request.eventKey()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toSuspensionEventResponse(saved));
                });
    }

    @Override
    @Transactional
    public ServiceResult resumeRace(String raceId, String eventKey, ResumeRaceRequest request) {
        return withIdempotency(request.requestId(), "RESUME_RACE",
                orderedParams(
                        "raceId", raceId,
                        "eventKey", eventKey,
                        "resumeElapsedMs", request.resumeElapsedMs(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = repository.findRaceForUpdate(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() != RaceStatus.SUSPENDED) {
                        throw new ConflictException("赛事未处于中止状态: " + raceId);
                    }
                    if (race.version() != request.expectedVersion()) {
                        throw new ConflictException("版本冲突: expected=" + request.expectedVersion()
                                + ", actual=" + race.version());
                    }
                    SuspensionEventRow event = repository.findSuspensionEvent(eventKey)
                            .orElseThrow(() -> new NotFoundException("中止事件不存在: " + eventKey));
                    if (!event.raceId().equals(raceId)) {
                        throw new NotFoundException("中止事件不属于该赛事: " + eventKey);
                    }
                    if (event.status() == SuspensionStatus.RESUMED) {
                        throw new ConflictException("中止事件已恢复: " + eventKey);
                    }
                    long resumeElapsedMs = request.resumeElapsedMs();
                    if (resumeElapsedMs <= event.startElapsedMs()) {
                        throw new BadRequestException("恢复耗时必须大于中止开始耗时");
                    }
                    // 在持有赛事行锁的同一事务内重算全部选手净分段、净完赛与漏点；
                    // 任一净不变量违反则抛422整体回滚，恢复事件不落库、版本不推进。
                    List<RunnerRow> runners = repository.findRunners(raceId);
                    List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
                    List<NetTimingCalculator.SuspensionEvent> eventViews = new ArrayList<>();
                    for (SuspensionEventRow existing : repository.findSuspensionEvents(raceId)) {
                        if (existing.eventKey().equals(eventKey)) {
                            eventViews.add(new NetTimingCalculator.SuspensionEvent(
                                    eventKey, existing.checkpointPosition(),
                                    existing.startElapsedMs(), resumeElapsedMs));
                        } else if (existing.status() == SuspensionStatus.RESUMED) {
                            eventViews.add(toEventView(existing));
                        }
                    }
                    Map<String, List<CheckpointTimingRow>> timingsByBib =
                            groupTimingsByBib(timings);
                    for (RunnerRow runner : runners) {
                        NetTimingCalculator.NetOutcome outcome =
                                NetTimingCalculator.computeRunnerNet(
                                        timingsByBib.getOrDefault(runner.bib(), List.of()),
                                        runner.finishTimeMs(), eventViews);
                        if (outcome.hasViolation()) {
                            throw new UnprocessableEntityException(
                                    "恢复后净计时不变量违反: bib=" + runner.bib()
                                            + ", " + outcome.violation());
                        }
                    }
                    int newVersion = request.expectedVersion() + 1;
                    int updated = repository.resumeRaceIfSuspendedAtVersion(
                            raceId, request.expectedVersion(), newVersion);
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事未处于中止状态");
                    }
                    long now = clock.millis();
                    repository.markSuspensionResumed(eventKey, resumeElapsedMs, now);
                    SuspensionEventRow saved =
                            repository.findSuspensionEvent(eventKey).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toSuspensionEventResponse(saved));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public SuspensionHistoryResponse getSuspensionEvents(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<com.example.starter.race.api.SuspensionEventResponse> events =
                repository.findSuspensionEvents(raceId).stream()
                        .map(ResponseMapper::toSuspensionEventResponse)
                        .toList();
        return new SuspensionHistoryResponse(raceId, race.version(), events);
    }

    @Override
    @Transactional(readOnly = true)
    public CompensationDetailResponse getCompensations(String raceId, String eventKey) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        SuspensionEventRow event = repository.findSuspensionEvent(eventKey)
                .orElseThrow(() -> new NotFoundException("中止事件不存在: " + eventKey));
        if (!event.raceId().equals(raceId)) {
            throw new NotFoundException("中止事件不属于该赛事: " + eventKey);
        }
        if (event.status() != SuspensionStatus.RESUMED) {
            throw new ConflictException("中止事件尚未恢复，补偿未确定: " + eventKey);
        }
        List<RunnerRow> runners = repository.findRunners(raceId);
        Map<String, NetTimingCalculator.RunnerNet> netByBib = computeNetForRunners(
                runners, repository.findAllTimings(raceId),
                repository.findSuspensionEvents(raceId));
        List<RunnerCompensationResponse> compensations = runners.stream()
                .map(runner -> {
                    NetTimingCalculator.Compensation compensation =
                            netByBib.get(runner.bib()).compensations().stream()
                                    .filter(item -> item.eventKey().equals(eventKey))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException(
                                            "缺少补偿明细: " + runner.bib()));
                    return new RunnerCompensationResponse(
                            runner.bib(), compensation.compensationMs(), compensation.basis());
                })
                .toList();
        return new CompensationDetailResponse(raceId, eventKey, compensations);
    }

    /**
     * 计算全部选手的净计时（净分段、净完赛、补偿明细）。
     * 读取与封榜路径上的不变量已由恢复时校验保证，此处再违反说明数据损坏。
     */
    private Map<String, NetTimingCalculator.RunnerNet> computeNetForRunners(
            List<RunnerRow> runners,
            List<CheckpointTimingRow> timings,
            List<SuspensionEventRow> events) {
        List<NetTimingCalculator.SuspensionEvent> eventViews = events.stream()
                .filter(event -> event.status() == SuspensionStatus.RESUMED)
                .map(RaceServiceImpl::toEventView)
                .toList();
        Map<String, List<CheckpointTimingRow>> timingsByBib = groupTimingsByBib(timings);
        Map<String, NetTimingCalculator.RunnerNet> netByBib = new java.util.HashMap<>();
        for (RunnerRow runner : runners) {
            NetTimingCalculator.NetOutcome outcome = NetTimingCalculator.computeRunnerNet(
                    timingsByBib.getOrDefault(runner.bib(), List.of()),
                    runner.finishTimeMs(), eventViews);
            if (outcome.hasViolation()) {
                throw new IllegalStateException(
                        "净计时不变量被破坏: " + runner.bib() + ", " + outcome.violation());
            }
            netByBib.put(runner.bib(), outcome.net());
        }
        return netByBib;
    }

    /** 把选手行包装为携带净完赛耗时的排名视图：原始值不变，排名以净值为准。 */
    private static List<ResultCalculator.RunnerView> netAdjustedViews(
            List<RunnerRow> runners, Map<String, NetTimingCalculator.RunnerNet> netByBib) {
        return runners.stream()
                .map(runner -> (ResultCalculator.RunnerView) new NetRunnerView(
                        runner, netByBib.get(runner.bib()).netFinishTimeMs()))
                .toList();
    }

    /** 新提交记录（分段或完赛）落在任一已恢复事件 [start,resume) 窗口内时拒绝（422）。 */
    private void rejectIfInSuspensionWindow(String raceId, Long elapsedMillis) {
        if (elapsedMillis == null) {
            return;
        }
        List<NetTimingCalculator.SuspensionEvent> eventViews =
                repository.findSuspensionEvents(raceId).stream()
                        .filter(event -> event.status() == SuspensionStatus.RESUMED)
                        .map(RaceServiceImpl::toEventView)
                        .toList();
        if (eventViews.isEmpty()) {
            return;
        }
        String violation = NetTimingCalculator.windowViolation(elapsedMillis, eventViews);
        if (violation != null) {
            throw new UnprocessableEntityException(violation);
        }
    }

    private static NetTimingCalculator.SuspensionEvent toEventView(SuspensionEventRow row) {
        return new NetTimingCalculator.SuspensionEvent(
                row.eventKey(), row.checkpointPosition(),
                row.startElapsedMs(), row.resumeElapsedMs());
    }

    private static Map<String, List<CheckpointTimingRow>> groupTimingsByBib(
            List<CheckpointTimingRow> timings) {
        Map<String, List<CheckpointTimingRow>> timingsByBib = new java.util.HashMap<>();
        for (CheckpointTimingRow timing : timings) {
            timingsByBib.computeIfAbsent(timing.bib(), key -> new ArrayList<>()).add(timing);
        }
        return timingsByBib;
    }

    /** 携带净完赛耗时的选手排名视图：原始完赛耗时不改写，净值用于排名。 */
    private record NetRunnerView(RunnerRow row, Long netFinishTimeMs)
            implements ResultCalculator.RunnerView {

        @Override
        public String bib() {
            return row.bib();
        }

        @Override
        public Long finishTimeMs() {
            return row.finishTimeMs();
        }
    }
}
