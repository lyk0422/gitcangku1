package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureInspectionRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.EquipmentBindingResponse;
import com.example.starter.race.api.EquipmentBindingsResponse;
import com.example.starter.race.api.InspectionConfigResponse;
import com.example.starter.race.api.InspectionHistoryResponse;
import com.example.starter.race.api.InspectionRecordResponse;
import com.example.starter.race.api.InspectionStatusResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerRaceStateResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StartRunnerRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitInspectionRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.InspectionGate;
import com.example.starter.race.domain.InspectionResult;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.RunnerRaceState;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.EquipmentBindingRow;
import com.example.starter.race.persistence.EquipmentInspectionRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceInspectionConfigRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRaceStateRow;
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
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertRunner(raceId, request.bib(), finishTimeMs, now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("参赛号已存在: " + request.bib());
                    }
                    // 为新选手建立起跑/退赛状态行：已登记未起跑。
                    repository.insertRunnerState(raceId, request.bib(), now);
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
                    // 修订后首次具备完赛耗时视为完赛，释放器材绑定（后续修订不再重复释放）。
                    if (runner.finishTimeMs() == null) {
                        releaseActiveBinding(raceId, request.bib(), "FINISHED", now);
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
                    // 取消资格立即生效：释放该选手器材绑定供他人复检 PASS 使用（撤销处罚不回收绑定）。
                    if (type == PenaltyType.DISQUALIFY) {
                        releaseActiveBinding(raceId, request.bib(), "DISQUALIFIED", now);
                    }
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
                    // 首个分段计时即起跑：未起跑时强制检录赛事先过起跑门禁（按可注入时钟）。
                    RunnerRaceStateRow state = currentRunnerState(raceId, bib);
                    if (state.state() == RunnerRaceState.WITHDRAWN) {
                        throw new ConflictException("选手已退赛，不能提交分段记录: " + bib);
                    }
                    boolean firstStart = state.state() == RunnerRaceState.REGISTERED;
                    if (firstStart) {
                        enforceInspectionGate(raceId, bib, clock.millis());
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    if (firstStart) {
                        // 门禁通过后将选手置为已起跑（显式起跑与首个分段计时共用同一状态转移）。
                        repository.markStartedIfRegistered(raceId, bib, now);
                    }
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
    public ServiceResult configureInspection(String raceId, ConfigureInspectionRequest request) {
        return withIdempotency(request.requestId(), "CONFIGURE_INSPECTION",
                orderedParams(
                        "raceId", raceId,
                        "mandatory", request.mandatory(),
                        "validMinutes", request.validMinutes(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    int validMinutes = request.validMinutes();
                    if (validMinutes < 1 || validMinutes > 1440) {
                        throw new BadRequestException("检录有效分钟数必须在 1~1440 之间");
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    repository.upsertInspectionConfig(
                            raceId, request.mandatory(), validMinutes, now);
                    RaceInspectionConfigRow config =
                            repository.findInspectionConfig(raceId).orElseThrow();
                    RaceRow refreshed = repository.findRace(raceId).orElseThrow();
                    return ServiceResult.ok(new InspectionConfigResponse(
                            raceId, refreshed.version(), config.mandatory(),
                            config.validMinutes(), config.createdAt(), config.updatedAt()));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitInspection(String raceId, String bib, SubmitInspectionRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_INSPECTION",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "inspectionKey", request.inspectionKey(),
                        "equipmentSerial", request.equipmentSerial(),
                        "result", request.result(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // inspectionKey 是第二层全局幂等键：同参重放原结果，异参409。
                    EquipmentInspectionRow existingInspection =
                            repository.findInspection(request.inspectionKey()).orElse(null);
                    if (existingInspection != null) {
                        return replayInspectionOrConflict(raceId, bib, request, existingInspection);
                    }

                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    if (currentRunnerState(raceId, bib).state() == RunnerRaceState.WITHDRAWN) {
                        throw new ConflictException("选手已退赛，不能提交检录: " + bib);
                    }
                    RaceInspectionConfigRow config = repository.findInspectionConfig(raceId)
                            .orElseThrow(() -> new UnprocessableEntityException(
                                    "赛事尚未配置器材检录: " + raceId));
                    InspectionResult result;
                    try {
                        result = InspectionResult.valueOf(request.result());
                    } catch (IllegalArgumentException ex) {
                        throw new BadRequestException("检录结果仅允许 PASS 或 FAIL: " + request.result());
                    }
                    long now = clock.millis();
                    Long validUntil = result == InspectionResult.PASS
                            ? now + config.validMinutes() * 60_000L
                            : null;
                    // 仅未完赛选手的 PASS 才占用器材绑定：未退赛、无生效取消资格、尚未申报完赛耗时。
                    // （登记时已带 finishTimeMs 视为已完赛；无耗时者在 reviseTime 补录完赛时释放。）
                    boolean occupiesBinding = result == InspectionResult.PASS
                            && runner.finishTimeMs() == null
                            && currentRunnerState(raceId, bib).state() != RunnerRaceState.WITHDRAWN
                            && !hasActiveDisqualification(raceId, bib);
                    // PASS 前先判定器材唯一性：同赛事同器材已绑定另一名未完赛选手即409。
                    EquipmentBindingRow serialBinding = occupiesBinding
                            ? repository.findActiveBinding(raceId, request.equipmentSerial())
                                    .orElse(null)
                            : null;
                    if (serialBinding != null && !serialBinding.bib().equals(bib)) {
                        throw new ConflictException(
                                "器材序列号已绑定另一名未完赛选手: " + request.equipmentSerial());
                    }
                    bumpVersion(race, request.expectedVersion());
                    EquipmentInspectionRow row = new EquipmentInspectionRow(
                            0L, request.inspectionKey(), raceId, bib,
                            request.equipmentSerial(), result, config.validMinutes(),
                            now, validUntil, now);
                    try {
                        repository.insertInspection(row);
                    } catch (DuplicateKeyException ex) {
                        EquipmentInspectionRow concurrent =
                                repository.findInspection(request.inspectionKey()).orElseThrow();
                        return replayInspectionOrConflict(raceId, bib, request, concurrent);
                    }
                    if (occupiesBinding) {
                        // 复检 PASS：先释放本人此前的活跃绑定（FAIL 阻断后复检或换新器材复检均覆盖）。
                        EquipmentBindingRow ownBinding =
                                repository.findActiveBindingForRunner(raceId, bib).orElse(null);
                        if (ownBinding != null) {
                            repository.releaseBinding(ownBinding.id(), "RE_INSPECTED", now);
                        }
                        try {
                            repository.insertBinding(
                                    raceId, request.equipmentSerial(), bib,
                                    request.inspectionKey(), now);
                        } catch (DuplicateKeyException ex) {
                            // 唯一槽约束兜底并发下的同器材二次 PASS。
                            throw new ConflictException(
                                    "器材序列号已绑定另一名未完赛选手: "
                                            + request.equipmentSerial());
                        }
                    }
                    EquipmentInspectionRow saved =
                            repository.findInspection(request.inspectionKey()).orElseThrow();
                    return ServiceResult.created(toInspectionResponse(saved));
                });
    }

    private ServiceResult replayInspectionOrConflict(
            String raceId,
            String bib,
            SubmitInspectionRequest request,
            EquipmentInspectionRow existing) {
        boolean sameParams = existing.raceId().equals(raceId)
                && existing.bib().equals(bib)
                && existing.equipmentSerial().equals(request.equipmentSerial())
                && existing.result().name().equals(request.result());
        if (!sameParams) {
            throw new ConflictException(
                    "inspectionKey 已用于不同参数的检录记录: " + request.inspectionKey());
        }
        return ServiceResult.created(toInspectionResponse(existing));
    }

    @Override
    @Transactional
    public ServiceResult startRunner(String raceId, String bib, StartRunnerRequest request) {
        return withIdempotency(request.requestId(), "START_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, bib);
                    RunnerRaceStateRow state = currentRunnerState(raceId, bib);
                    if (state.state() == RunnerRaceState.WITHDRAWN) {
                        throw new ConflictException("选手已退赛，不能起跑: " + bib);
                    }
                    if (state.state() == RunnerRaceState.STARTED) {
                        throw new ConflictException("选手已起跑: " + bib);
                    }
                    long now = clock.millis();
                    // 强制检录赛事：不存在、已过期或最新结果 FAIL 均422，不写入计时/起跑。
                    enforceInspectionGate(raceId, bib, now);
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.markStartedIfRegistered(raceId, bib, now);
                    if (updated == 0) {
                        throw new ConflictException("选手状态已变化，起跑失败: " + bib);
                    }
                    return ServiceResult.ok(toRunnerStateResponse(
                            repository.findRunnerState(raceId, bib).orElseThrow()));
                });
    }

    @Override
    @Transactional
    public ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request) {
        return withIdempotency(request.requestId(), "WITHDRAW_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, bib);
                    RunnerRaceStateRow state = currentRunnerState(raceId, bib);
                    if (state.state() == RunnerRaceState.WITHDRAWN) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.markWithdrawnIfNotWithdrawn(
                            raceId, bib, request.reason(), now);
                    if (updated == 0) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    // 退赛释放器材绑定，其他选手此后可绑定该序列号。
                    releaseActiveBinding(raceId, bib, "WITHDRAWN", now);
                    return ServiceResult.ok(toRunnerStateResponse(
                            repository.findRunnerState(raceId, bib).orElseThrow()));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionHistoryResponse getInspectionHistory(String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        List<InspectionRecordResponse> records =
                repository.findInspectionsForRunner(raceId, bib).stream()
                        .map(this::toInspectionResponse)
                        .toList();
        return new InspectionHistoryResponse(raceId, bib, records);
    }

    @Override
    @Transactional(readOnly = true)
    public InspectionStatusResponse getInspectionStatus(String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        RaceInspectionConfigRow config = repository.findInspectionConfig(raceId).orElse(null);
        long now = clock.millis();
        EquipmentInspectionRow latest =
                repository.findLatestInspection(raceId, bib).orElse(null);
        boolean mandatory = config != null && config.mandatory();
        String effective;
        if (!mandatory) {
            effective = "NOT_REQUIRED";
        } else if (latest == null) {
            effective = "NONE";
        } else if (latest.result() == InspectionResult.FAIL) {
            effective = "FAIL";
        } else if (now > latest.validUntil()) {
            effective = "EXPIRED";
        } else {
            effective = "PASS_VALID";
        }
        return new InspectionStatusResponse(
                raceId, bib, mandatory, effective,
                latest == null ? null : toInspectionResponse(latest), now);
    }

    @Override
    @Transactional(readOnly = true)
    public EquipmentBindingsResponse getEquipmentBindings(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<EquipmentBindingResponse> bindings = repository.findActiveBindings(raceId).stream()
                .map(row -> new EquipmentBindingResponse(
                        row.equipmentSerial(), row.bib(), row.inspectionId(), row.boundAt()))
                .toList();
        return new EquipmentBindingsResponse(raceId, bindings);
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerRaceStateResponse getRunnerState(String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        return toRunnerStateResponse(currentRunnerState(raceId, bib));
    }

    /**
     * 强制检录起跑门禁：非强制/未配置赛事直接放行；
     * 否则按可注入时钟校验最近一条检录，未通过抛 422（调用方不得继续写入）。
     */
    private void enforceInspectionGate(String raceId, String bib, long now) {
        RaceInspectionConfigRow config = repository.findInspectionConfig(raceId).orElse(null);
        boolean mandatory = config != null && config.mandatory();
        EquipmentInspectionRow latest =
                repository.findLatestInspection(raceId, bib).orElse(null);
        InspectionGate.Verdict verdict = InspectionGate.evaluate(mandatory, latest, now);
        if (verdict != InspectionGate.Verdict.ALLOWED) {
            throw new UnprocessableEntityException(InspectionGate.reasonOf(verdict));
        }
    }

    /** 释放选手当前活跃器材绑定；无活跃绑定时为空操作。 */
    private void releaseActiveBinding(
            String raceId, String bib, String releaseReason, long now) {
        repository.findActiveBindingForRunner(raceId, bib)
                .ifPresent(binding ->
                        repository.releaseBinding(binding.id(), releaseReason, now));
    }

    /** 选手是否存在生效（未撤销）的取消资格处罚；生效 DQ 视为已退出竞争，不占用器材绑定。 */
    private boolean hasActiveDisqualification(String raceId, String bib) {
        return repository.findPenalties(raceId).stream()
                .anyMatch(penalty -> penalty.bib().equals(bib)
                        && penalty.type() == PenaltyType.DISQUALIFY
                        && !penalty.revoked());
    }

    /** 读取选手起跑/退赛状态；缺少状态行（历史数据）按已登记未起跑处理。 */
    private RunnerRaceStateRow currentRunnerState(String raceId, String bib) {
        return repository.findRunnerState(raceId, bib).orElseGet(() ->
                new RunnerRaceStateRow(
                        raceId, bib, RunnerRaceState.REGISTERED, null, null, null, 0L, 0L));
    }

    private InspectionRecordResponse toInspectionResponse(EquipmentInspectionRow row) {
        return new InspectionRecordResponse(
                row.inspectionId(), row.bib(), row.equipmentSerial(), row.result().name(),
                row.validMinutes(), row.inspectedAt(), row.validUntil());
    }

    private RunnerRaceStateResponse toRunnerStateResponse(RunnerRaceStateRow row) {
        return new RunnerRaceStateResponse(
                row.raceId(), row.bib(), row.state().name(),
                row.startedAt(), row.withdrawnAt(), row.reason(), row.updatedAt());
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
}
