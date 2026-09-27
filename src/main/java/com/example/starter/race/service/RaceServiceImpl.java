package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.ApproveReclassRequest;
import com.example.starter.race.api.CategoryResponse;
import com.example.starter.race.api.CategoryStandingResponse;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateCategoryRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.ReclassApplicationResponse;
import com.example.starter.race.api.ReclassApprovalResponse;
import com.example.starter.race.api.ReclassRecordResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RejectReclassRequest;
import com.example.starter.race.api.ResultEntryResponse;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealCategoryRequest;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.CategoryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.RunnerStatus;
import com.example.starter.race.persistence.CategoryRow;
import com.example.starter.race.persistence.CategorySnapshotEntryRow;
import com.example.starter.race.persistence.CategorySnapshotRow;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.ReclassRecordRow;
import com.example.starter.race.persistence.ReclassRequestRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
                    String categoryCode = request.categoryCode();
                    if (categoryCode != null) {
                        CategoryRow category = repository.findCategory(raceId, categoryCode)
                                .orElseThrow(() -> new UnprocessableEntityException(
                                        "目标组别不存在: " + categoryCode));
                        if (category.status() == CategoryStatus.SEALED) {
                            throw new UnprocessableEntityException(
                                    "目标组别已封榜: " + categoryCode);
                        }
                        if (category.capacity() != null
                                && repository.countActiveRunnersInCategory(raceId, categoryCode)
                                        >= category.capacity()) {
                            throw new UnprocessableEntityException(
                                    "目标组别容量已满: " + categoryCode);
                        }
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertRunner(raceId, request.bib(), categoryCode, finishTimeMs, now);
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
                    List<RunnerRow> runners = repository.findRunners(raceId);
                    List<PenaltyRow> penalties = repository.findPenalties(raceId);
                    List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
                    List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
                    List<ResultEntry> entries =
                            ResultCalculator.compute(runners, penalties, checkpoints, timings);

                    int newVersion = request.expectedVersion() + 1;
                    int updated = repository.sealIfOpenAtVersion(
                            raceId, request.expectedVersion(), newVersion);
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事已封榜");
                    }
                    long now = clock.millis();
                    // 封榜仅固化提交时刻选手所属组别；历史快照不随后续重分追溯重写。
                    Map<String, String> categoryByBib = new TreeMap<>();
                    for (RunnerRow runner : runners) {
                        categoryByBib.put(runner.bib(), runner.categoryCode());
                    }
                    List<SnapshotEntryRow> snapshotEntries = new ArrayList<>(entries.size());
                    for (int order = 0; order < entries.size(); order++) {
                        ResultEntry entry = entries.get(order);
                        snapshotEntries.add(new SnapshotEntryRow(
                                raceId,
                                entry.bib(),
                                categoryByBib.get(entry.bib()),
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
                    // 在同一封榜事务内固化每名选手 × 每个检查点的明细，缺失行耗时为 null。
                    List<SnapshotCheckpointRow> snapshotCheckpoints =
                            buildSnapshotCheckpoints(raceId, runners, checkpoints, timings);
                    repository.insertSnapshot(new SnapshotRow(raceId, newVersion, now,
                            snapshotEntries, snapshotCheckpoints));
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
        return ResponseMapper.liveStanding(
                race,
                repository.findRunners(raceId),
                repository.findPenalties(raceId),
                repository.findCheckpoints(raceId),
                repository.findAllTimings(raceId));
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
        return ResponseMapper.runnerTiming(
                race, runner, checkpoints, repository.findTimingsForRunner(raceId, bib));
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
     * 已有通过记录固化耗时与 timingId，缺失检查点对应字段为 null；顺序为参赛号、检查点顺序。
     */
    private List<SnapshotCheckpointRow> buildSnapshotCheckpoints(
            String raceId,
            List<RunnerRow> runners,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
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
                    byBibAndCode.getOrDefault(runner.bib(), Map.of()));
        }
        return rows;
    }

    private void appendSnapshotCheckpointRows(
            List<SnapshotCheckpointRow> rows,
            String raceId,
            String bib,
            List<CheckpointRow> checkpoints,
            Map<String, CheckpointTimingRow> timingByCode) {
        for (CheckpointRow checkpoint : checkpoints) {
            CheckpointTimingRow timing = timingByCode.get(checkpoint.checkpointCode());
            if (timing == null) {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), null, null));
            } else {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), timing.elapsedMillis(), timing.timingId()));
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
    public ServiceResult createCategory(String raceId, CreateCategoryRequest request) {
        return withIdempotency(request.requestId(), "CREATE_CATEGORY",
                orderedParams(
                        "raceId", raceId,
                        "categoryCode", request.categoryCode(),
                        "capacity", request.capacity(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    if (request.capacity() != null && request.capacity() < 1) {
                        throw new BadRequestException("组别容量必须为正整数");
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertCategory(
                                raceId, request.categoryCode(), request.capacity(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("组别已存在: " + request.categoryCode());
                    }
                    CategoryRow category =
                            repository.findCategory(raceId, request.categoryCode()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toCategoryResponse(category, 0));
                });
    }

    @Override
    @Transactional
    public ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request) {
        return withIdempotency(request.requestId(), "WITHDRAW_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    if (runner.status() == RunnerStatus.WITHDRAWN) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.withdrawRunnerIfActive(raceId, bib, now);
                    if (updated == 0) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    RunnerRow refreshed = repository.findRunner(raceId, bib).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toRunnerResponse(refreshed));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitReclassApplication(
            String raceId, SubmitReclassApplicationRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_RECLASS",
                orderedParams(
                        "raceId", raceId,
                        "applicationId", request.applicationId(),
                        "bib", request.bib(),
                        "targetCategory", request.targetCategory(),
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, request.bib());
                    if (runner.status() == RunnerStatus.WITHDRAWN) {
                        throw new ConflictException("选手已退赛，不能申请组别重分: " + request.bib());
                    }
                    CategoryRow target = repository.findCategory(raceId, request.targetCategory())
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "目标组别不存在: " + request.targetCategory()));
                    if (target.status() == CategoryStatus.SEALED) {
                        throw new UnprocessableEntityException(
                                "目标组别已封榜: " + request.targetCategory());
                    }
                    if (request.targetCategory().equals(runner.categoryCode())) {
                        throw new UnprocessableEntityException(
                                "目标组别与当前组别相同: " + request.targetCategory());
                    }
                    if (repository.countPendingReclassRequests(raceId, request.bib()) > 0) {
                        throw new ConflictException("选手已存在待审批的重分申请: " + request.bib());
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    ReclassRequestRow row = new ReclassRequestRow(
                            request.applicationId(), raceId, request.bib(),
                            request.targetCategory(), request.reason(), request.expectedVersion(),
                            ReclassRequestRow.ReclassRequestStatus.PENDING, null, now, null);
                    try {
                        repository.insertReclassRequest(row);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("申请ID已存在: " + request.applicationId());
                    }
                    ReclassRequestRow saved =
                            repository.findReclassRequest(request.applicationId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toReclassApplicationResponse(saved));
                });
    }

    @Override
    @Transactional
    public ServiceResult rejectReclassApplication(
            String raceId, String applicationId, RejectReclassRequest request) {
        return withIdempotency(request.requestId(), "REJECT_RECLASS",
                orderedParams(
                        "raceId", raceId,
                        "applicationId", applicationId,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    ReclassRequestRow application = requireReclassRequest(raceId, applicationId);
                    if (application.status() != ReclassRequestRow.ReclassRequestStatus.PENDING) {
                        throw new ConflictException("申请已审批，不能拒绝: " + applicationId);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    // 仅状态迁移，不触碰任何成绩数据。
                    int updated = repository.decideReclassRequestIfPending(
                            applicationId, ReclassRequestRow.ReclassRequestStatus.REJECTED,
                            null, now);
                    if (updated == 0) {
                        throw new ConflictException("申请已审批，不能拒绝: " + applicationId);
                    }
                    ReclassRequestRow refreshed =
                            repository.findReclassRequest(applicationId).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toReclassApplicationResponse(refreshed));
                });
    }

    private ReclassRequestRow requireReclassRequest(String raceId, String applicationId) {
        ReclassRequestRow application = repository.findReclassRequest(applicationId)
                .orElseThrow(() -> new NotFoundException("重分申请不存在: " + applicationId));
        if (!application.raceId().equals(raceId)) {
            throw new NotFoundException("重分申请不属于该赛事: " + applicationId);
        }
        return application;
    }

    private RaceRow requireRace(String raceId) {
        return repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
    }

    @Override
    @Transactional
    public ServiceResult approveReclassApplications(String raceId, ApproveReclassRequest request) {
        // reclassKey 指纹：操作者 + 赛事版本 + 规范化申请集合（排序去重）+ 规范化目标组别集合；
        // 同键同参重放完整排名结果，异参409，业务失败随事务回滚不占键。
        List<String> normalizedIds =
                request.applicationIds().stream().sorted().distinct().toList();
        List<String> targetCategories = normalizedIds.stream()
                .map(id -> repository.findReclassRequest(id)
                        .map(ReclassRequestRow::targetCategory)
                        .orElse("<missing:" + id + ">"))
                .sorted()
                .distinct()
                .toList();
        return withIdempotency(request.reclassKey(), "APPROVE_RECLASS",
                orderedParams(
                        "raceId", raceId,
                        "operator", request.operator(),
                        "expectedVersion", request.expectedVersion(),
                        "applicationIds", normalizedIds,
                        "targetCategories", targetCategories),
                () -> doApproveReclass(raceId, request, normalizedIds));
    }

    private ServiceResult doApproveReclass(
            String raceId, ApproveReclassRequest request, List<String> normalizedIds) {
        RaceRow race = requireOpenRace(raceId, request.expectedVersion());
        List<ReclassRequestRow> applications = new ArrayList<>(normalizedIds.size());
        for (String applicationId : normalizedIds) {
            applications.add(requireReclassRequest(raceId, applicationId));
        }
        // 整批静态校验：任一不合法整批失败，不产生部分迁移。
        Set<String> seenBibs = new HashSet<>();
        for (ReclassRequestRow application : applications) {
            if (application.status() != ReclassRequestRow.ReclassRequestStatus.PENDING) {
                throw new ConflictException("申请非待审批状态: " + application.applicationId());
            }
            if (!seenBibs.add(application.bib())) {
                throw new UnprocessableEntityException(
                        "同一参赛者在批内仅允许出现一次: " + application.bib());
            }
        }
        List<RunnerRow> runners = repository.findRunners(raceId);
        Map<String, RunnerRow> runnerByBib = new HashMap<>();
        for (RunnerRow runner : runners) {
            runnerByBib.put(runner.bib(), runner);
        }
        Map<String, CategoryRow> categoryByCode = new HashMap<>();
        for (CategoryRow category : repository.findCategories(raceId)) {
            categoryByCode.put(category.categoryCode(), category);
        }
        for (ReclassRequestRow application : applications) {
            RunnerRow runner = runnerByBib.get(application.bib());
            if (runner == null) {
                throw new NotFoundException("选手不存在: " + application.bib());
            }
            if (runner.status() == RunnerStatus.WITHDRAWN) {
                throw new ConflictException("参赛者已退赛，不能批准重分: " + application.bib());
            }
            CategoryRow target = categoryByCode.get(application.targetCategory());
            if (target == null) {
                throw new UnprocessableEntityException(
                        "目标组别不存在: " + application.targetCategory());
            }
            if (target.status() == CategoryStatus.SEALED) {
                throw new UnprocessableEntityException(
                        "目标组别已封榜: " + application.targetCategory());
            }
            if (application.targetCategory().equals(runner.categoryCode())) {
                throw new UnprocessableEntityException(
                        "目标组别与当前组别相同: " + application.targetCategory());
            }
            if (runner.categoryCode() != null) {
                CategoryRow source = categoryByCode.get(runner.categoryCode());
                if (source != null && source.status() == CategoryStatus.SEALED) {
                    throw new ConflictException("来源组别已封榜: " + runner.categoryCode());
                }
            }
        }
        // 容量校验：目标组别当前在赛人数 + 批内净迁入 ≤ 容量（NULL 容量不限）。
        Map<String, Integer> headcountByCategory = new HashMap<>();
        for (RunnerRow runner : runners) {
            if (runner.status() == RunnerStatus.ACTIVE && runner.categoryCode() != null) {
                headcountByCategory.merge(runner.categoryCode(), 1, Integer::sum);
            }
        }
        Map<String, Integer> deltaByCategory = new HashMap<>();
        for (ReclassRequestRow application : applications) {
            deltaByCategory.merge(application.targetCategory(), 1, Integer::sum);
            String source = runnerByBib.get(application.bib()).categoryCode();
            if (source != null) {
                deltaByCategory.merge(source, -1, Integer::sum);
            }
        }
        for (String targetCode : deltaByCategory.keySet()) {
            CategoryRow target = categoryByCode.get(targetCode);
            if (target != null && target.capacity() != null) {
                int resulting = headcountByCategory.getOrDefault(targetCode, 0)
                        + deltaByCategory.get(targetCode);
                if (resulting > target.capacity()) {
                    throw new UnprocessableEntityException(
                            "目标组别容量超限: " + targetCode);
                }
            }
        }
        // 迁移前名次与计时快照：按来源组别当前榜单计算（未分组选手无来源榜单）。
        List<PenaltyRow> penalties = repository.findPenalties(raceId);
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
        Set<String> involvedCategories = new TreeSet<>();
        for (ReclassRequestRow application : applications) {
            involvedCategories.add(application.targetCategory());
            String source = runnerByBib.get(application.bib()).categoryCode();
            if (source != null) {
                involvedCategories.add(source);
            }
        }
        Map<String, List<ResultEntry>> prevEntriesByCategory = new HashMap<>();
        for (String categoryCode : involvedCategories) {
            prevEntriesByCategory.put(categoryCode, computeCategoryEntries(
                    categoryCode, runners, penalties, checkpoints, timings));
        }
        Map<String, Long> activePenaltyByBib = new HashMap<>();
        for (PenaltyRow penalty : penalties) {
            if (!penalty.revoked() && penalty.type() == PenaltyType.ADD_TIME
                    && penalty.amountMs() != null) {
                activePenaltyByBib.merge(penalty.bib(), penalty.amountMs(), Long::sum);
            }
        }
        long now = clock.millis();
        bumpVersion(race, request.expectedVersion());
        List<ReclassRecordRow> records = new ArrayList<>(applications.size());
        for (ReclassRequestRow application : applications) {
            RunnerRow runner = runnerByBib.get(application.bib());
            ResultEntry prevEntry = null;
            if (runner.categoryCode() != null) {
                prevEntry = prevEntriesByCategory.get(runner.categoryCode()).stream()
                        .filter(entry -> entry.bib().equals(application.bib()))
                        .findFirst()
                        .orElse(null);
            }
            int decided = repository.decideReclassRequestIfPending(
                    application.applicationId(),
                    ReclassRequestRow.ReclassRequestStatus.APPROVED,
                    request.reclassKey(), now);
            if (decided == 0) {
                throw new ConflictException("申请已被并发审批: " + application.applicationId());
            }
            repository.updateRunnerCategory(
                    raceId, application.bib(), application.targetCategory(), now);
            records.add(buildReclassRecord(
                    request.reclassKey(), raceId, application, runner, prevEntry,
                    activePenaltyByBib.getOrDefault(application.bib(), 0L), now));
        }
        repository.insertReclassRecords(records);
        // 重算来源与目标组别排名（迁移后），作为完整排名结果返回并被幂等记录固化。
        List<RunnerRow> migratedRunners = repository.findRunners(raceId);
        Map<String, List<ResultEntryResponse>> standings = new TreeMap<>();
        for (String categoryCode : involvedCategories) {
            standings.put(categoryCode, computeCategoryEntries(
                    categoryCode, migratedRunners, penalties, checkpoints, timings)
                    .stream()
                    .map(ResponseMapper::toEntryResponse)
                    .toList());
        }
        return ServiceResult.ok(new ReclassApprovalResponse(
                raceId, request.expectedVersion() + 1, request.reclassKey(),
                records.stream().map(ResponseMapper::toReclassRecordResponse).toList(),
                standings));
    }

    /** 组装不可变重分记录：来源组别内迁移前名次与计时快照；未分组选手无来源名次。 */
    private static ReclassRecordRow buildReclassRecord(
            String batchId,
            String raceId,
            ReclassRequestRow application,
            RunnerRow runner,
            ResultEntry prevEntry,
            long activePenaltyMs,
            long now) {
        if (prevEntry != null) {
            return new ReclassRecordRow(
                    batchId, application.bib(), raceId,
                    runner.categoryCode(), application.targetCategory(),
                    prevEntry.rank(), prevEntry.status(), prevEntry.finishTimeMs(),
                    prevEntry.penaltyMs(), prevEntry.totalTimeMs(), now);
        }
        Long finishTimeMs = runner.finishTimeMs();
        return new ReclassRecordRow(
                batchId, application.bib(), raceId,
                runner.categoryCode(), application.targetCategory(),
                null, null, finishTimeMs, activePenaltyMs,
                finishTimeMs == null ? null : finishTimeMs + activePenaltyMs,
                now);
    }

    /** 计算某组别当前榜单（该组别全部选手，退赛选手以 WITHDRAWN 状态列出不排名）。 */
    private List<ResultEntry> computeCategoryEntries(
            String categoryCode,
            List<RunnerRow> runners,
            List<PenaltyRow> penalties,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        List<RunnerRow> categoryRunners = runners.stream()
                .filter(runner -> categoryCode.equals(runner.categoryCode()))
                .toList();
        return ResultCalculator.compute(categoryRunners, penalties, checkpoints, timings);
    }

    @Override
    @Transactional
    public ServiceResult sealCategory(
            String raceId, String categoryCode, SealCategoryRequest request) {
        return withIdempotency(request.requestId(), "SEAL_CATEGORY",
                orderedParams(
                        "raceId", raceId,
                        "categoryCode", categoryCode,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    CategoryRow category = repository.findCategory(raceId, categoryCode)
                            .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
                    if (category.status() == CategoryStatus.SEALED) {
                        throw new ConflictException("组别已封榜: " + categoryCode);
                    }
                    // 仅固化封榜时刻归属该组别的选手成绩；历史快照不追溯重写。
                    List<ResultEntry> entries = computeCategoryEntries(
                            categoryCode,
                            repository.findRunnersByCategory(raceId, categoryCode),
                            repository.findPenalties(raceId),
                            repository.findCheckpoints(raceId),
                            repository.findAllTimings(raceId));
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int sealed = repository.sealCategoryIfOpen(raceId, categoryCode, now);
                    if (sealed == 0) {
                        throw new ConflictException("组别已封榜: " + categoryCode);
                    }
                    int newVersion = request.expectedVersion() + 1;
                    List<CategorySnapshotEntryRow> snapshotEntries =
                            new ArrayList<>(entries.size());
                    for (int order = 0; order < entries.size(); order++) {
                        ResultEntry entry = entries.get(order);
                        snapshotEntries.add(new CategorySnapshotEntryRow(
                                raceId, categoryCode, entry.bib(), entry.rank(), entry.status(),
                                entry.finishTimeMs(), entry.penaltyMs(), entry.totalTimeMs(),
                                order));
                    }
                    repository.insertCategorySnapshot(new CategorySnapshotRow(
                            raceId, categoryCode, newVersion, now, snapshotEntries));
                    return ServiceResult.ok(new CategoryStandingResponse(
                            raceId, categoryCode, newVersion, CategoryStatus.SEALED, now,
                            snapshotEntries.stream()
                                    .map(ResponseMapper::toEntryResponse)
                                    .toList()));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(String raceId) {
        requireRace(raceId);
        return repository.findCategories(raceId).stream()
                .map(category -> ResponseMapper.toCategoryResponse(category,
                        repository.countActiveRunnersInCategory(raceId, category.categoryCode())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReclassApplicationResponse> listReclassApplications(String raceId) {
        requireRace(raceId);
        return repository.findReclassRequests(raceId).stream()
                .map(ResponseMapper::toReclassApplicationResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReclassRecordResponse> listReclassRecords(String raceId) {
        requireRace(raceId);
        return repository.findReclassRecords(raceId).stream()
                .map(ResponseMapper::toReclassRecordResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryStandingResponse getCategoryStandings(String raceId, String categoryCode) {
        RaceRow race = requireRace(raceId);
        CategoryRow category = repository.findCategory(raceId, categoryCode)
                .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
        if (category.status() == CategoryStatus.SEALED) {
            CategorySnapshotRow snapshot =
                    repository.findCategorySnapshot(raceId, categoryCode)
                            .orElseThrow(() -> new IllegalStateException(
                                    "组别已封榜但缺少快照: " + categoryCode));
            return ResponseMapper.snapshotCategoryStanding(snapshot);
        }
        List<ResultEntry> entries = computeCategoryEntries(
                categoryCode,
                repository.findRunnersByCategory(raceId, categoryCode),
                repository.findPenalties(raceId),
                repository.findCheckpoints(raceId),
                repository.findAllTimings(raceId));
        return ResponseMapper.liveCategoryStanding(race, category, entries);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryStandingResponse> listCategorySnapshots(String raceId) {
        requireRace(raceId);
        return repository.findCategorySnapshots(raceId).stream()
                .map(ResponseMapper::snapshotCategoryStanding)
                .toList();
    }
}
