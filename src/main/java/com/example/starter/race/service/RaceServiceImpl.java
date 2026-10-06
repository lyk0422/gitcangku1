package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicatePursuitRequest;
import com.example.starter.race.api.AdjudicationResponse;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigurePursuitRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.PursuitConfigResponse;
import com.example.starter.race.api.PursuitPairResponse;
import com.example.starter.race.api.PursuitSnapshotPairResponse;
import com.example.starter.race.api.PursuitSnapshotResponse;
import com.example.starter.race.api.PursuitSnapshotSampleResponse;
import com.example.starter.race.api.PursuitStateResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.SubmitTrajectoryRequest;
import com.example.starter.race.api.TrajectoryResponse;
import com.example.starter.race.domain.CatchTime;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.PursuitCatchCalculator;
import com.example.starter.race.domain.RaceMode;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TrajectoryPoint;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.PursuitAdjudicationRow;
import com.example.starter.race.persistence.PursuitPairRow;
import com.example.starter.race.persistence.PursuitRepository;
import com.example.starter.race.persistence.PursuitSampleRow;
import com.example.starter.race.persistence.PursuitTrajectoryRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotPursuitPairRow;
import com.example.starter.race.persistence.SnapshotPursuitSampleRow;
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
    private final PursuitRepository pursuitRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public RaceServiceImpl(RaceRepository repository, PursuitRepository pursuitRepository,
                           Clock clock, ObjectMapper objectMapper) {
        this.repository = repository;
        this.pursuitRepository = pursuitRepository;
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
                            race.raceId(), race.version(), race.status(), race.mode(),
                            race.createdAt()));
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
                    // 追逐赛禁止直接录入完赛耗时：成绩由轨迹裁定产生，原单条成绩入口不得绕过。
                    if (race.mode() == RaceMode.PURSUIT && finishTimeMs != null) {
                        throw new ConflictException("追逐赛禁止直接录入完赛耗时: " + raceId);
                    }
                    validateFinishTime(finishTimeMs, true);
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
                    if (race.mode() == RaceMode.PURSUIT) {
                        throw new ConflictException("追逐赛禁止直接修订完赛耗时: " + raceId);
                    }
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
                    if (race.mode() == RaceMode.PURSUIT) {
                        throw new ConflictException("追逐赛不模拟赛道，禁止配置检查点: " + raceId);
                    }
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
                    if (race.mode() == RaceMode.PURSUIT) {
                        throw new ConflictException("追逐赛不模拟赛道，禁止提交分段记录: " + raceId);
                    }
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

                    // 追逐赛封榜前置：所有声明追逐对都必须有当前裁定（轨迹更正会删除旧裁定，
                    // 因此此处存在的裁定必然对应当前轨迹版本）；在同一事务内冻结完整轨迹点与交会分数。
                    List<SnapshotPursuitPairRow> pursuitSnapshotPairs = List.of();
                    List<SnapshotPursuitSampleRow> pursuitSnapshotSamples = List.of();
                    if (race.mode() == RaceMode.PURSUIT) {
                        pursuitSnapshotPairs = buildPursuitSnapshotPairs(raceId);
                        pursuitSnapshotSamples = pursuitRepository.findAllSamples(raceId).stream()
                                .map(sample -> new SnapshotPursuitSampleRow(
                                        raceId, sample.bib(), sample.seq(),
                                        sample.timeMs(), sample.distanceMm()))
                                .toList();
                    }

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
                    if (race.mode() == RaceMode.PURSUIT) {
                        pursuitRepository.insertSnapshotPursuit(
                                pursuitSnapshotPairs, pursuitSnapshotSamples);
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

    @Override
    @Transactional
    public ServiceResult configurePursuit(String raceId, ConfigurePursuitRequest request) {
        List<ConfigurePursuitRequest.PursuitPairDefinition> definitions =
                request.pairs().stream()
                        .sorted(java.util.Comparator
                                .comparingInt(ConfigurePursuitRequest.PursuitPairDefinition::pairNo))
                        .toList();
        return withIdempotency(request.requestId(), "CONFIGURE_PURSUIT",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion(),
                        "operator", request.operator(),
                        "pairs", definitions.stream()
                                .map(def -> def.pairNo() + ":" + def.chaserBib() + ":"
                                        + def.chasedBib() + ":" + def.chaserStartMm() + ":"
                                        + def.chasedStartMm())
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    if (race.mode() == RaceMode.PURSUIT) {
                        throw new ConflictException("赛事已启用PURSUIT: " + raceId);
                    }
                    // 必须在计时前启用：已有任何完赛耗时或分段记录即拒绝。
                    if (repository.countTimedRunners(raceId) > 0
                            || repository.countTimings(raceId) > 0) {
                        throw new ConflictException("赛事已开始计时，不能启用PURSUIT: " + raceId);
                    }
                    validatePursuitPairs(raceId, definitions);
                    int newVersion = request.expectedVersion() + 1;
                    int updated = repository.enablePursuitIfStandardAtVersion(
                            raceId, request.expectedVersion(), newVersion);
                    if (updated == 0) {
                        throw new ConflictException("版本冲突或赛事已启用PURSUIT");
                    }
                    long now = clock.millis();
                    List<PursuitPairRow> rows = definitions.stream()
                            .map(def -> new PursuitPairRow(
                                    raceId, def.pairNo(), def.chaserBib(), def.chasedBib(),
                                    def.chaserStartMm(), def.chasedStartMm(), now,
                                    request.operator()))
                            .toList();
                    pursuitRepository.insertPairs(rows);
                    return ServiceResult.created(new PursuitConfigResponse(
                            raceId, newVersion,
                            rows.stream().map(RaceServiceImpl::toPairResponse).toList()));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitTrajectory(
            String raceId, String bib, SubmitTrajectoryRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_TRAJECTORY",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "trajectoryVersion", request.trajectoryVersion(),
                        "expectedVersion", request.expectedVersion(),
                        "operator", request.operator(),
                        "samples", request.samples().stream()
                                .map(sample -> sample.timeMs() + ":" + sample.distanceMm())
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requirePursuitMode(race);
                    requireRunner(raceId, bib);
                    validateSamples(request.samples());
                    Optional<PursuitTrajectoryRow> current =
                            pursuitRepository.findTrajectory(raceId, bib);
                    int newTrajectoryVersion;
                    if (current.isEmpty()) {
                        if (request.trajectoryVersion() != 0) {
                            throw new ConflictException("轨迹版本冲突: 该选手尚无轨迹，expected="
                                    + request.trajectoryVersion());
                        }
                        newTrajectoryVersion = 1;
                    } else {
                        if (current.get().version() != request.trajectoryVersion()) {
                            throw new ConflictException("轨迹版本冲突: expected="
                                    + request.trajectoryVersion()
                                    + ", actual=" + current.get().version());
                        }
                        newTrajectoryVersion = current.get().version() + 1;
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    if (current.isEmpty()) {
                        pursuitRepository.insertTrajectory(new PursuitTrajectoryRow(
                                raceId, bib, newTrajectoryVersion, now, request.operator()));
                    } else {
                        pursuitRepository.bumpTrajectoryVersion(
                                raceId, bib, newTrajectoryVersion, now, request.operator());
                    }
                    List<PursuitSampleRow> rows = new ArrayList<>(request.samples().size());
                    for (int i = 0; i < request.samples().size(); i++) {
                        SubmitTrajectoryRequest.SamplePoint sample = request.samples().get(i);
                        rows.add(new PursuitSampleRow(
                                raceId, bib, i + 1, sample.timeMs(), sample.distanceMm()));
                    }
                    pursuitRepository.replaceSamples(raceId, bib, rows);
                    // 轨迹更正使涉及该选手的旧裁定失效（同事务删除，封榜须重新裁定）。
                    pursuitRepository.deleteAdjudicationsForBib(raceId, bib);
                    return ServiceResult.created(new TrajectoryResponse(
                            raceId, bib, newTrajectoryVersion,
                            rows.stream()
                                    .map(row -> new TrajectoryPoint(row.timeMs(), row.distanceMm()))
                                    .toList()));
                });
    }

    @Override
    @Transactional
    public ServiceResult adjudicatePursuit(String raceId, AdjudicatePursuitRequest request) {
        return withIdempotency(request.requestId(), "ADJUDICATE_PURSUIT",
                orderedParams(
                        "raceId", raceId,
                        "pairNo", request.pairNo(),
                        "chaserTrajectoryVersion", request.chaserTrajectoryVersion(),
                        "chasedTrajectoryVersion", request.chasedTrajectoryVersion(),
                        "expectedVersion", request.expectedVersion(),
                        "operator", request.operator()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requirePursuitMode(race);
                    PursuitPairRow pair = pursuitRepository
                            .findPair(raceId, request.pairNo())
                            .orElseThrow(() -> new NotFoundException(
                                    "追逐对不存在: " + request.pairNo()));
                    PursuitTrajectoryRow chaserTrajectory = pursuitRepository
                            .findTrajectory(raceId, pair.chaserBib())
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "追赶者缺少轨迹: " + pair.chaserBib()));
                    PursuitTrajectoryRow chasedTrajectory = pursuitRepository
                            .findTrajectory(raceId, pair.chasedBib())
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "被追者缺少轨迹: " + pair.chasedBib()));
                    // 裁定必须基于双方当前轨迹版本，与并发更正不混版本。
                    if (chaserTrajectory.version() != request.chaserTrajectoryVersion()
                            || chasedTrajectory.version() != request.chasedTrajectoryVersion()) {
                        throw new ConflictException("轨迹版本冲突: chaser expected="
                                + request.chaserTrajectoryVersion() + ", actual="
                                + chaserTrajectory.version() + "; chased expected="
                                + request.chasedTrajectoryVersion() + ", actual="
                                + chasedTrajectory.version());
                    }
                    if (pair.chaserStartMm() >= pair.chasedStartMm()) {
                        throw new UnprocessableEntityException(
                                "追赶者必须起始落后: chaserStartMm=" + pair.chaserStartMm()
                                        + ", chasedStartMm=" + pair.chasedStartMm());
                    }
                    List<TrajectoryPoint> chaserSamples = toPoints(
                            pursuitRepository.findSamples(raceId, pair.chaserBib()));
                    List<TrajectoryPoint> chasedSamples = toPoints(
                            pursuitRepository.findSamples(raceId, pair.chasedBib()));
                    CatchTime catchTime = PursuitCatchCalculator.earliestCatch(
                            pair.chaserStartMm(), pair.chasedStartMm(),
                            chaserSamples, chasedSamples)
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "双方轨迹公共区间内未交会，不予裁定"));
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    PursuitAdjudicationRow row = new PursuitAdjudicationRow(
                            raceId, pair.pairNo(), pair.chaserBib(), pair.chasedBib(),
                            chaserTrajectory.version(), chasedTrajectory.version(),
                            catchTime.numerator(), catchTime.denominator(), now,
                            request.operator());
                    pursuitRepository.replaceAdjudication(row);
                    return ServiceResult.created(toAdjudicationResponse(row));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public PursuitStateResponse getPursuitState(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.mode() != RaceMode.PURSUIT) {
            throw new NotFoundException("赛事未启用PURSUIT: " + raceId);
        }
        List<PursuitPairResponse> pairs = pursuitRepository.findPairs(raceId).stream()
                .map(RaceServiceImpl::toPairResponse)
                .toList();
        List<TrajectoryResponse> trajectories = pursuitRepository.findTrajectories(raceId).stream()
                .map(trajectory -> new TrajectoryResponse(
                        raceId, trajectory.bib(), trajectory.version(),
                        toPoints(pursuitRepository.findSamples(raceId, trajectory.bib()))))
                .toList();
        List<AdjudicationResponse> adjudications =
                pursuitRepository.findAdjudications(raceId).stream()
                        .map(RaceServiceImpl::toAdjudicationResponse)
                        .toList();
        return new PursuitStateResponse(raceId, race.version(), pairs, trajectories, adjudications);
    }

    @Override
    @Transactional(readOnly = true)
    public PursuitSnapshotResponse getPursuitSnapshot(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.mode() != RaceMode.PURSUIT) {
            throw new NotFoundException("赛事未启用PURSUIT: " + raceId);
        }
        SnapshotRow snapshot = repository.findSnapshot(raceId)
                .orElseThrow(() -> new NotFoundException("赛事尚未封榜: " + raceId));
        List<PursuitSnapshotPairResponse> pairs =
                pursuitRepository.findSnapshotPursuitPairs(raceId).stream()
                        .map(row -> new PursuitSnapshotPairResponse(
                                row.pairNo(), row.chaserBib(), row.chasedBib(),
                                row.chaserStartMm(), row.chasedStartMm(),
                                row.chaserTrajectoryVersion(), row.chasedTrajectoryVersion(),
                                row.catchTimeNum(), row.catchTimeDen()))
                        .toList();
        List<PursuitSnapshotSampleResponse> samples =
                pursuitRepository.findSnapshotPursuitSamples(raceId).stream()
                        .map(row -> new PursuitSnapshotSampleResponse(
                                row.bib(), row.seq(), row.timeMs(), row.distanceMm()))
                        .toList();
        return new PursuitSnapshotResponse(
                raceId, snapshot.version(), snapshot.sealedAt(), pairs, samples);
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

    /** 要求赛事为追逐模式，否则拒绝写入。 */
    private static void requirePursuitMode(RaceRow race) {
        if (race.mode() != RaceMode.PURSUIT) {
            throw new ConflictException("赛事未启用PURSUIT: " + race.raceId());
        }
    }

    /**
     * 校验追逐对配置：1~5对、pairNo 从1连续递增、同对两人不同、起始距离非负，
     * 且双方都是本赛事已登记选手。
     */
    private void validatePursuitPairs(
            String raceId, List<ConfigurePursuitRequest.PursuitPairDefinition> definitions) {
        if (definitions.isEmpty() || definitions.size() > 5) {
            throw new BadRequestException("追逐对数量必须在 1~5 之间");
        }
        for (int i = 0; i < definitions.size(); i++) {
            ConfigurePursuitRequest.PursuitPairDefinition def = definitions.get(i);
            if (def.pairNo() != i + 1) {
                throw new BadRequestException("追逐对序号必须从1连续递增，缺少 pairNo=" + (i + 1));
            }
            if (def.chaserBib().equals(def.chasedBib())) {
                throw new BadRequestException("同一追逐对的追赶者与被追者不能相同: " + def.chaserBib());
            }
            if (def.chaserStartMm() < 0 || def.chasedStartMm() < 0) {
                throw new BadRequestException("起始距离必须为非负整数毫米");
            }
            requireRunner(raceId, def.chaserBib());
            requireRunner(raceId, def.chasedBib());
        }
    }

    /**
     * 校验轨迹采样：1~10点；起点固定为(0,0)，故首点时刻必须为正；
     * 时刻严格递增、累计距离非负且不减。
     */
    private static void validateSamples(List<SubmitTrajectoryRequest.SamplePoint> samples) {
        if (samples.isEmpty() || samples.size() > 10) {
            throw new BadRequestException("采样点数量必须在 1~10 之间");
        }
        long previousTimeMs = 0L;
        long previousDistanceMm = 0L;
        for (SubmitTrajectoryRequest.SamplePoint sample : samples) {
            if (sample.timeMs() <= previousTimeMs) {
                throw new BadRequestException("采样时刻必须为正且严格递增: " + sample.timeMs());
            }
            if (sample.distanceMm() < 0 || sample.distanceMm() < previousDistanceMm) {
                throw new BadRequestException("累计前进距离必须非负且不减: " + sample.distanceMm());
            }
            previousTimeMs = sample.timeMs();
            previousDistanceMm = sample.distanceMm();
        }
    }

    /** 封榜前置：为每个声明追逐对取当前裁定并组装冻结行；任一追逐对缺少当前裁定即422。 */
    private List<SnapshotPursuitPairRow> buildPursuitSnapshotPairs(String raceId) {
        List<PursuitPairRow> pairs = pursuitRepository.findPairs(raceId);
        Map<Integer, PursuitAdjudicationRow> adjudicationByPairNo = new TreeMap<>();
        for (PursuitAdjudicationRow adjudication : pursuitRepository.findAdjudications(raceId)) {
            adjudicationByPairNo.put(adjudication.pairNo(), adjudication);
        }
        List<SnapshotPursuitPairRow> rows = new ArrayList<>(pairs.size());
        for (PursuitPairRow pair : pairs) {
            PursuitAdjudicationRow adjudication = adjudicationByPairNo.get(pair.pairNo());
            if (adjudication == null) {
                throw new UnprocessableEntityException(
                        "追逐对缺少当前裁定，不能封榜: pairNo=" + pair.pairNo());
            }
            rows.add(new SnapshotPursuitPairRow(
                    raceId, pair.pairNo(), pair.chaserBib(), pair.chasedBib(),
                    pair.chaserStartMm(), pair.chasedStartMm(),
                    adjudication.chaserTrajectoryVersion(),
                    adjudication.chasedTrajectoryVersion(),
                    adjudication.catchTimeNum(), adjudication.catchTimeDen()));
        }
        return rows;
    }

    private static List<TrajectoryPoint> toPoints(List<PursuitSampleRow> rows) {
        return rows.stream()
                .map(row -> new TrajectoryPoint(row.timeMs(), row.distanceMm()))
                .toList();
    }

    private static PursuitPairResponse toPairResponse(PursuitPairRow row) {
        return new PursuitPairResponse(
                row.pairNo(), row.chaserBib(), row.chasedBib(),
                row.chaserStartMm(), row.chasedStartMm());
    }

    private static AdjudicationResponse toAdjudicationResponse(PursuitAdjudicationRow row) {
        return new AdjudicationResponse(
                row.raceId(), row.pairNo(), row.chaserBib(), row.chasedBib(),
                row.chaserTrajectoryVersion(), row.chasedTrajectoryVersion(),
                row.catchTimeNum(), row.catchTimeDen(), row.createdAt());
    }
}
