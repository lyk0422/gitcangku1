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
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitReclassApplicationRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ReclassPlanner;
import com.example.starter.race.domain.ReclassRequestStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                        "categoryCode", request.categoryCode(),
                        "expectedVersion", request.expectedVersion(),
                        "finishTimeMs", request.finishTimeMs()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    Long finishTimeMs = request.finishTimeMs();
                    validateFinishTime(finishTimeMs, true);
                    String categoryCode = request.categoryCode();
                    if (categoryCode != null) {
                        CategoryRow category = repository.findCategory(raceId, categoryCode)
                                .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
                        if (category.status() == RaceStatus.SEALED) {
                            throw new ConflictException("组别已封榜，不能登记新选手: " + categoryCode);
                        }
                        if (repository.countActiveRunnersInCategory(raceId, categoryCode)
                                >= category.capacity()) {
                            throw new UnprocessableEntityException("组别容量已满: " + categoryCode);
                        }
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertRunner(raceId, request.bib(), finishTimeMs, categoryCode, now);
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
                    // 封榜仅固化在赛选手；退赛选手不进入快照。
                    List<RunnerRow> runners = activeRunners(repository.findRunners(raceId));
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
                    List<SnapshotEntryRow> snapshotEntries = new ArrayList<>(entries.size());
                    for (int order = 0; order < entries.size(); order++) {
                        ResultEntry entry = entries.get(order);
                        snapshotEntries.add(new SnapshotEntryRow(
                                raceId,
                                entry.bib(),
                                entry.categoryCode(),
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
                    // 赛事封榜同时冻结所有仍开放的组别：各组别写入只读快照，
                    // 快照固化封榜时刻所属组别，历史快照不追溯重写。
                    for (CategoryRow category : repository.findCategories(raceId)) {
                        if (category.status() != RaceStatus.OPEN) {
                            continue;
                        }
                        repository.sealCategoryIfOpen(raceId, category.categoryCode(), now);
                        repository.insertCategorySnapshot(buildCategorySnapshot(
                                raceId, category.categoryCode(), newVersion, now));
                    }
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
        // 退赛选手不参与排名；其处罚与分段因无对应选手条目自然被忽略。
        return ResponseMapper.liveStanding(
                race,
                activeRunners(repository.findRunners(raceId)),
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
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    try {
                        repository.insertCategory(
                                raceId, request.categoryCode(), request.capacity(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("组别已存在: " + request.categoryCode());
                    }
                    CategoryRow category = repository
                            .findCategory(raceId, request.categoryCode()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toCategoryResponse(category));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(String raceId) {
        requireRace(raceId);
        return repository.findCategories(raceId).stream()
                .map(ResponseMapper::toCategoryResponse)
                .toList();
    }

    @Override
    @Transactional
    public ServiceResult sealCategory(String raceId, String categoryCode, SealRaceRequest request) {
        return withIdempotency(request.requestId(), "SEAL_CATEGORY",
                orderedParams(
                        "raceId", raceId,
                        "categoryCode", categoryCode,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    CategoryRow category = repository.findCategory(raceId, categoryCode)
                            .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
                    if (category.status() == RaceStatus.SEALED) {
                        throw new ConflictException("组别已封榜: " + categoryCode);
                    }
                    int newVersion = request.expectedVersion() + 1;
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    int updated = repository.sealCategoryIfOpen(raceId, categoryCode, now);
                    if (updated == 0) {
                        throw new ConflictException("组别已封榜: " + categoryCode);
                    }
                    repository.insertCategorySnapshot(
                            buildCategorySnapshot(raceId, categoryCode, newVersion, now));
                    CategorySnapshotRow snapshot = repository
                            .findCategorySnapshot(raceId, categoryCode).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.snapshotCategoryStanding(snapshot));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryStandingResponse getCategoryResults(String raceId, String categoryCode) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        CategoryRow category = repository.findCategory(raceId, categoryCode)
                .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
        if (category.status() == RaceStatus.SEALED) {
            CategorySnapshotRow snapshot = repository.findCategorySnapshot(raceId, categoryCode)
                    .orElseThrow(() -> new IllegalStateException(
                            "组别已封榜但缺少快照: " + categoryCode));
            return ResponseMapper.snapshotCategoryStanding(snapshot);
        }
        return computeCategoryStanding(raceId, categoryCode, race.version());
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryStandingResponse getCategorySnapshot(String raceId, String categoryCode) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        CategorySnapshotRow snapshot = repository.findCategorySnapshot(raceId, categoryCode)
                .orElseThrow(() -> new NotFoundException("组别尚未封榜: " + categoryCode));
        return ResponseMapper.snapshotCategoryStanding(snapshot);
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
                    if (runner.withdrawn()) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    int updated = repository.markRunnerWithdrawn(raceId, bib, now);
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
                        "targetCategoryCode", request.targetCategoryCode(),
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, request.bib());
                    if (runner.withdrawn()) {
                        throw new UnprocessableEntityException(
                                "退赛选手不能提交组别重分申请: " + request.bib());
                    }
                    CategoryRow target = repository
                            .findCategory(raceId, request.targetCategoryCode())
                            .orElseThrow(() -> new NotFoundException(
                                    "目标组别不存在: " + request.targetCategoryCode()));
                    if (target.status() == RaceStatus.SEALED) {
                        throw new ConflictException(
                                "目标组别已封榜: " + request.targetCategoryCode());
                    }
                    if (request.targetCategoryCode().equals(runner.categoryCode())) {
                        throw new UnprocessableEntityException(
                                "选手已在目标组别: " + request.targetCategoryCode());
                    }
                    if (repository.findPendingReclassRequest(raceId, request.bib()).isPresent()) {
                        throw new ConflictException(
                                "选手已存在待审批的重分申请: " + request.bib());
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    ReclassRequestRow row = new ReclassRequestRow(
                            request.applicationId(), raceId, request.bib(),
                            runner.categoryCode(), request.targetCategoryCode(),
                            request.reason(), request.expectedVersion(),
                            ReclassRequestStatus.PENDING, now, null);
                    try {
                        repository.insertReclassRequest(row);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("申请ID已存在: " + request.applicationId());
                    }
                    ReclassRequestRow saved =
                            repository.findReclassRequest(request.applicationId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toApplicationResponse(saved));
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
                    ReclassRequestRow application = repository.findReclassRequest(applicationId)
                            .filter(found -> found.raceId().equals(raceId))
                            .orElseThrow(() -> new NotFoundException(
                                    "重分申请不存在: " + applicationId));
                    if (application.status() != ReclassRequestStatus.PENDING) {
                        throw new ConflictException("申请已审批，不能重复拒绝: " + applicationId);
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    repository.markReclassRequestStatus(
                            applicationId, ReclassRequestStatus.REJECTED, now);
                    ReclassRequestRow saved =
                            repository.findReclassRequest(applicationId).orElseThrow();
                    // 拒绝仅流转申请状态，不触碰任何成绩数据。
                    return ServiceResult.ok(ResponseMapper.toApplicationResponse(saved));
                });
    }
    @Override
    @Transactional
    public ServiceResult approveReclass(String raceId, ApproveReclassRequest request) {
        // 规范化申请集合：按申请ID排序去重；指纹含操作者、赛事版本、申请集合与目标组别。
        List<String> applicationIds = request.applicationIds().stream()
                .sorted()
                .distinct()
                .toList();
        // 指纹计算前解析目标组别；申请不存在属于失败，不占用 reclassKey。
        List<ReclassRequestRow> applications = applicationIds.stream()
                .map(id -> repository.findReclassRequest(id)
                        .filter(found -> found.raceId().equals(raceId))
                        .orElseThrow(() -> new NotFoundException("重分申请不存在: " + id)))
                .toList();
        return withIdempotency(request.reclassKey(), "APPROVE_RECLASS",
                orderedParams(
                        "raceId", raceId,
                        "operator", request.operator(),
                        "expectedVersion", request.expectedVersion(),
                        "applications", applications.stream()
                                .map(row -> row.applicationId() + "->" + row.targetCategoryCode())
                                .toList()),
                () -> doApproveReclass(raceId, request, applications));
    }

    /**
     * 批量批准的同事务实现：整批校验（422）与状态冲突（409）通过后，
     * 一次性迁移全部选手、写入不可变重分记录并重算受影响组别排名。
     */
    private ServiceResult doApproveReclass(
            String raceId,
            ApproveReclassRequest request,
            List<ReclassRequestRow> applications) {
        RaceRow race = requireOpenRace(raceId, request.expectedVersion());
        // 在赛事行锁内重新加载申请与选手，保证校验基于串行化后的最新状态。
        applications = applications.stream()
                .map(row -> repository.findReclassRequest(row.applicationId()).orElseThrow())
                .toList();
        for (ReclassRequestRow application : applications) {
            if (application.status() != ReclassRequestStatus.PENDING) {
                throw new UnprocessableEntityException(
                        "申请不是待审批状态: " + application.applicationId());
            }
        }
        Set<String> bibs = new HashSet<>();
        for (ReclassRequestRow application : applications) {
            if (!bibs.add(application.bib())) {
                throw new UnprocessableEntityException(
                        "同一参赛者在批次中重复: " + application.bib());
            }
        }
        Map<String, CategoryRow> categoriesByCode = new TreeMap<>();
        for (CategoryRow category : repository.findCategories(raceId)) {
            categoriesByCode.put(category.categoryCode(), category);
        }
        List<RunnerRow> runners = applications.stream()
                .map(row -> requireRunner(raceId, row.bib()))
                .toList();
        for (int i = 0; i < applications.size(); i++) {
            ReclassRequestRow application = applications.get(i);
            RunnerRow runner = runners.get(i);
            CategoryRow target = categoriesByCode.get(application.targetCategoryCode());
            if (target == null) {
                throw new UnprocessableEntityException(
                        "目标组别不存在: " + application.targetCategoryCode());
            }
            if (target.status() == RaceStatus.SEALED) {
                throw new UnprocessableEntityException(
                        "申请涉及已封榜组别: " + application.targetCategoryCode());
            }
            CategoryRow source = runner.categoryCode() == null
                    ? null
                    : categoriesByCode.get(runner.categoryCode());
            if (source != null && source.status() == RaceStatus.SEALED) {
                throw new ConflictException("选手当前组别已封榜: " + runner.categoryCode());
            }
            if (runner.withdrawn()) {
                throw new ConflictException("选手已退赛，不能批准重分: " + runner.bib());
            }
            if (application.targetCategoryCode().equals(runner.categoryCode())) {
                throw new UnprocessableEntityException(
                        "选手已在目标组别: " + application.targetCategoryCode());
            }
        }
        Map<String, Integer> activeCounts = new TreeMap<>();
        Map<String, Integer> capacities = new TreeMap<>();
        for (CategoryRow category : categoriesByCode.values()) {
            activeCounts.put(category.categoryCode(),
                    repository.countActiveRunnersInCategory(raceId, category.categoryCode()));
            capacities.put(category.categoryCode(), category.capacity());
        }
        List<ReclassPlanner.Migration> migrations = new ArrayList<>(applications.size());
        for (int i = 0; i < applications.size(); i++) {
            migrations.add(new ReclassPlanner.Migration(
                    runners.get(i).categoryCode(), applications.get(i).targetCategoryCode()));
        }
        String capacityViolation =
                ReclassPlanner.firstCapacityViolation(activeCounts, capacities, migrations);
        if (capacityViolation != null) {
            throw new UnprocessableEntityException("目标组别容量不足: " + capacityViolation);
        }

        // 迁移前名次与计时快照：按来源组别分别计算当前排名；无来源组别按原始数据取快照。
        Map<String, List<ResultEntry>> standingsBeforeByCategory = new TreeMap<>();
        for (RunnerRow runner : runners) {
            String sourceCode = runner.categoryCode();
            if (sourceCode != null && !standingsBeforeByCategory.containsKey(sourceCode)) {
                standingsBeforeByCategory.put(sourceCode,
                        computeCategoryEntries(raceId, sourceCode));
            }
        }
        List<PenaltyRow> penalties = repository.findPenalties(raceId);

        int newVersion = request.expectedVersion() + 1;
        bumpVersion(race, request.expectedVersion());
        long now = clock.millis();
        List<ReclassRecordRow> records = new ArrayList<>(applications.size());
        Set<String> affectedCategories = new LinkedHashSet<>();
        for (int i = 0; i < applications.size(); i++) {
            ReclassRequestRow application = applications.get(i);
            RunnerRow runner = runners.get(i);
            String fromCategory = runner.categoryCode();
            Integer rankBefore = null;
            Long finishTimeMs = runner.finishTimeMs();
            long penaltyMs = activePenaltyMs(penalties, runner.bib());
            Long totalTimeMs = finishTimeMs == null ? null : finishTimeMs + penaltyMs;
            if (fromCategory != null) {
                ResultEntry before = standingsBeforeByCategory.get(fromCategory).stream()
                        .filter(entry -> entry.bib().equals(runner.bib()))
                        .findFirst()
                        .orElseThrow();
                rankBefore = before.rank();
                finishTimeMs = before.finishTimeMs();
                penaltyMs = before.penaltyMs();
                totalTimeMs = before.totalTimeMs();
                affectedCategories.add(fromCategory);
            }
            affectedCategories.add(application.targetCategoryCode());
            repository.updateRunnerCategory(raceId, runner.bib(), application.targetCategoryCode());
            repository.markReclassRequestStatus(
                    application.applicationId(), ReclassRequestStatus.APPROVED, now);
            records.add(new ReclassRecordRow(
                    request.reclassKey() + ":" + application.applicationId(),
                    raceId,
                    request.reclassKey(),
                    application.applicationId(),
                    runner.bib(),
                    fromCategory,
                    application.targetCategoryCode(),
                    rankBefore,
                    finishTimeMs,
                    penaltyMs,
                    totalTimeMs,
                    request.operator(),
                    newVersion,
                    now));
        }
        repository.insertReclassRecords(records);

        List<CategoryStandingResponse> standings = affectedCategories.stream()
                .sorted()
                .map(code -> computeCategoryStanding(raceId, code, newVersion))
                .toList();
        return ServiceResult.ok(new ReclassApprovalResponse(
                raceId,
                newVersion,
                request.reclassKey(),
                request.operator(),
                records.stream().map(ResponseMapper::toRecordResponse).toList(),
                standings));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReclassApplicationResponse> listReclassApplications(String raceId) {
        requireRace(raceId);
        return repository.findReclassRequests(raceId).stream()
                .map(ResponseMapper::toApplicationResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReclassRecordResponse> listReclassRecords(String raceId) {
        requireRace(raceId);
        return repository.findReclassRecords(raceId).stream()
                .map(ResponseMapper::toRecordResponse)
                .toList();
    }

    /** 赛事存在性校验（只读查询用）。 */
    private RaceRow requireRace(String raceId) {
        return repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
    }

    /** 过滤出在赛（未退赛）选手。 */
    private static List<RunnerRow> activeRunners(List<RunnerRow> runners) {
        return runners.stream().filter(runner -> !runner.withdrawn()).toList();
    }

    /** 某选手当前生效（未撤销）的加时处罚合计毫秒数。 */
    private static long activePenaltyMs(List<PenaltyRow> penalties, String bib) {
        long sum = 0L;
        for (PenaltyRow penalty : penalties) {
            if (penalty.bib().equals(bib) && !penalty.revoked()
                    && penalty.type() == PenaltyType.ADD_TIME && penalty.amountMs() != null) {
                sum += penalty.amountMs();
            }
        }
        return sum;
    }

    /** 计算某组别在赛选手的成绩条目（实时）。 */
    private List<ResultEntry> computeCategoryEntries(String raceId, String categoryCode) {
        List<RunnerRow> categoryRunners =
                repository.findActiveRunnersByCategory(raceId, categoryCode);
        Set<String> categoryBibs = new HashSet<>();
        for (RunnerRow runner : categoryRunners) {
            categoryBibs.add(runner.bib());
        }
        List<PenaltyRow> penalties = repository.findPenalties(raceId).stream()
                .filter(penalty -> categoryBibs.contains(penalty.bib()))
                .toList();
        List<CheckpointTimingRow> timings = repository.findAllTimings(raceId).stream()
                .filter(timing -> categoryBibs.contains(timing.bib()))
                .toList();
        return ResultCalculator.compute(
                categoryRunners, penalties, repository.findCheckpoints(raceId), timings);
    }

    /** 计算某组别的实时成绩榜响应。 */
    private CategoryStandingResponse computeCategoryStanding(
            String raceId, String categoryCode, int version) {
        CategoryRow category = repository.findCategory(raceId, categoryCode)
                .orElseThrow(() -> new NotFoundException("组别不存在: " + categoryCode));
        List<ResultEntry> entries = computeCategoryEntries(raceId, categoryCode);
        return new CategoryStandingResponse(
                raceId,
                categoryCode,
                version,
                category.status(),
                null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /** 由实时组别排名构造封榜快照行（含展示顺序）。 */
    private CategorySnapshotRow buildCategorySnapshot(
            String raceId, String categoryCode, int version, long now) {
        List<ResultEntry> entries = computeCategoryEntries(raceId, categoryCode);
        List<CategorySnapshotEntryRow> snapshotEntries = new ArrayList<>(entries.size());
        for (int order = 0; order < entries.size(); order++) {
            ResultEntry entry = entries.get(order);
            snapshotEntries.add(new CategorySnapshotEntryRow(
                    raceId,
                    categoryCode,
                    entry.bib(),
                    entry.rank(),
                    entry.status(),
                    entry.finishTimeMs(),
                    entry.penaltyMs(),
                    entry.totalTimeMs(),
                    order));
        }
        return new CategorySnapshotRow(raceId, categoryCode, version, now, snapshotEntries);
    }
}