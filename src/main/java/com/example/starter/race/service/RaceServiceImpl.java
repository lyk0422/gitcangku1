package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.InspectionResult;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.RunnerLifecycleStatus;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.EquipmentBindingRow;
import com.example.starter.race.persistence.EquipmentInspectionRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.InspectionConfigRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RaceStartRow;
import com.example.starter.race.persistence.RunnerLifecycleRow;
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
    /** 检录 PASS 有效分钟数上界（含端点）。 */
    private static final int MAX_INSPECTION_VALID_MINUTES = 1440;
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
        boolean required = Boolean.TRUE.equals(request.inspectionRequired());
        Integer validMinutes = request.validMinutes();
        if (required && validMinutes == null) {
            throw new BadRequestException("强制检录赛事必须提供 validMinutes（1~1440）");
        }
        if (required && (validMinutes < 1 || validMinutes > MAX_INSPECTION_VALID_MINUTES)) {
            throw new BadRequestException("检录有效分钟数必须在 1~1440 之间");
        }
        if (!required && validMinutes != null) {
            throw new BadRequestException("非强制检录赛事不应提供 validMinutes");
        }
        return withIdempotency(request.requestId(), "CREATE_RACE",
                orderedParams(
                        "raceId", request.raceId(),
                        "inspectionRequired", required,
                        "validMinutes", validMinutes),
                () -> {
                    long now = clock.millis();
                    try {
                        repository.insertRace(request.raceId(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("赛事已存在: " + request.raceId());
                    }
                    if (required) {
                        repository.insertInspectionConfig(new InspectionConfigRow(
                                request.raceId(), true, validMinutes, now));
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
                    // 完赛（录入原始完赛耗时）视为“已结束”，释放器材绑定。
                    repository.deleteBindingsForRunner(raceId, request.bib());
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
                    // 取消资格视为“已结束”，立即释放该选手的器材绑定。
                    if (type == PenaltyType.DISQUALIFY) {
                        repository.deleteBindingsForRunner(raceId, request.bib());
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
                    // 首个分段计时与起跑共用检录门禁：强制检录赛事首条分段前须存在未过期PASS。
                    if (runnerTimings.isEmpty()) {
                        enforceInspectionGate(raceId, bib);
                    }
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
    public ServiceResult submitInspection(String raceId, String bib,
            com.example.starter.race.api.SubmitInspectionRequest request) {
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
                    EquipmentInspectionRow existing =
                            repository.findInspection(request.inspectionKey()).orElse(null);
                    if (existing != null) {
                        return replayInspectionOrConflict(raceId, bib, request, existing);
                    }

                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    requireNotWithdrawn(raceId, bib);
                    InspectionResult result = parseInspectionResult(request.result());
                    InspectionConfigRow config = repository.findInspectionConfig(raceId)
                            .orElse(null);
                    long now = clock.millis();
                    int validMinutes;
                    Long validUntil = null;
                    if (result == InspectionResult.PASS) {
                        if (config == null) {
                            throw new UnprocessableEntityException(
                                    "赛事未配置检录，无法提交PASS检录: " + raceId);
                        }
                        validMinutes = config.validMinutes();
                        validUntil = now + validMinutes * 60_000L;
                        // 器材绑定：同器材序列号赛事内同时只能绑定一个未完赛选手。
                        EquipmentBindingRow binding =
                                repository.findBinding(raceId, request.equipmentSerial())
                                        .orElse(null);
                        if (binding != null && !binding.bib().equals(bib)) {
                            if (isRunnerFinished(raceId, binding.bib())) {
                                // 持有者已退赛/取消资格/完赛：释放旧绑定后由本次PASS接管。
                                repository.deleteBindingsForRunner(raceId, binding.bib());
                            } else {
                                throw new ConflictException(
                                        "器材序列号已被未完赛选手绑定: "
                                                + request.equipmentSerial());
                            }
                        }
                    } else {
                        // FAIL 不携带有效分钟数，仅作阻断；历史仍按不可变追加。
                        validMinutes = config == null ? 0 : config.validMinutes();
                    }
                    bumpVersion(race, request.expectedVersion());
                    EquipmentInspectionRow row = new EquipmentInspectionRow(
                            request.inspectionKey(), raceId, bib, request.equipmentSerial(),
                            result, validMinutes, now, validUntil, now);
                    try {
                        repository.insertInspection(row);
                    } catch (DuplicateKeyException ex) {
                        // 并发下同 inspectionKey 的唯一约束兜底：重放或异参冲突。
                        EquipmentInspectionRow concurrent =
                                repository.findInspection(request.inspectionKey()).orElseThrow();
                        return replayInspectionOrConflict(raceId, bib, request, concurrent);
                    }
                    if (result == InspectionResult.PASS) {
                        // 同一选手复检PASS会替换其此前绑定（旧绑定行先释放）。
                        repository.deleteBindingsForRunner(raceId, bib);
                        try {
                            repository.insertBinding(new EquipmentBindingRow(
                                    raceId, request.equipmentSerial(), bib,
                                    request.inspectionKey(), now));
                        } catch (DuplicateKeyException ex) {
                            throw new ConflictException(
                                    "器材序列号已被未完赛选手绑定: " + request.equipmentSerial());
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
            com.example.starter.race.api.SubmitInspectionRequest request,
            EquipmentInspectionRow existing) {
        boolean sameParams = existing.raceId().equals(raceId)
                && existing.bib().equals(bib)
                && existing.equipmentSerial().equals(request.equipmentSerial())
                && existing.result().name().equals(request.result());
        if (!sameParams) {
            throw new ConflictException(
                    "inspectionKey 已用于不同参数的检录: " + request.inspectionKey());
        }
        return ServiceResult.created(toInspectionResponse(existing));
    }

    private static com.example.starter.race.api.InspectionResponse toInspectionResponse(
            EquipmentInspectionRow row) {
        return new com.example.starter.race.api.InspectionResponse(
                row.inspectionId(), row.bib(), row.equipmentSerial(), row.result(),
                row.result() == InspectionResult.PASS ? row.validMinutes() : null,
                row.inspectedAt(), row.validUntil());
    }

    private static InspectionResult parseInspectionResult(String result) {
        try {
            return InspectionResult.valueOf(result);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("检录结果仅接受 PASS 或 FAIL: " + result);
        }
    }

    /** 选手已退赛则抛422（终态不可再检录/起跑）。 */
    private void requireNotWithdrawn(String raceId, String bib) {
        RunnerLifecycleRow lifecycle = repository.findLifecycle(raceId, bib).orElse(null);
        if (lifecycle != null && lifecycle.status() == RunnerLifecycleStatus.WITHDRAWN) {
            throw new UnprocessableEntityException("选手已退赛，禁止检录/起跑: " + bib);
        }
    }

    /**
     * 判断选手是否“已结束”（退赛/取消资格/完赛）：已结束才释放器材绑定。
     * 完赛=存在原始完赛耗时；取消资格=存在生效的 DISQUALIFY 处罚。
     */
    private boolean isRunnerFinished(String raceId, String bib) {
        RunnerLifecycleRow lifecycle = repository.findLifecycle(raceId, bib).orElse(null);
        if (lifecycle != null && lifecycle.status() == RunnerLifecycleStatus.WITHDRAWN) {
            return true;
        }
        RunnerRow runner = repository.findRunner(raceId, bib).orElse(null);
        if (runner != null && runner.finishTimeMs() != null) {
            return true;
        }
        for (PenaltyRow penalty : repository.findPenalties(raceId)) {
            if (penalty.bib().equals(bib) && !penalty.revoked()
                    && penalty.type() == PenaltyType.DISQUALIFY) {
                return true;
            }
        }
        return false;
    }


    @Override
    @Transactional
    public ServiceResult startRunner(String raceId, String bib,
            com.example.starter.race.api.StartRunnerRequest request) {
        return withIdempotency(request.requestId(), "START_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "startId", request.startId(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // startId 是第二层全局幂等键：同参重放原结果，异参409。
                    RaceStartRow existingStart = repository.findStart(request.startId()).orElse(null);
                    if (existingStart != null) {
                        return replayStartOrConflict(raceId, bib, request, existingStart);
                    }

                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, bib);
                    requireNotWithdrawn(raceId, bib);
                    if (repository.findStartForRunner(raceId, bib).isPresent()) {
                        throw new ConflictException("选手已起跑: " + bib);
                    }
                    // 起跑门禁：强制检录赛事须存在未过期PASS，否则422且不写起跑/计时。
                    enforceInspectionGate(raceId, bib);
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    RaceStartRow row = new RaceStartRow(request.startId(), raceId, bib, now);
                    try {
                        repository.insertStart(row);
                    } catch (DuplicateKeyException ex) {
                        if (constraintMatches(ex, "pk_race_start")) {
                            RaceStartRow concurrent =
                                    repository.findStart(request.startId()).orElseThrow();
                            return replayStartOrConflict(raceId, bib, request, concurrent);
                        }
                        throw new ConflictException("选手已起跑: " + bib);
                    }
                    upsertLifecycle(raceId, bib, RunnerLifecycleStatus.STARTED, now, now);
                    RaceStartRow saved = repository.findStart(request.startId()).orElseThrow();
                    return ServiceResult.created(new com.example.starter.race.api.StartResponse(
                            saved.startId(), saved.bib(), saved.startedAt()));
                });
    }

    private ServiceResult replayStartOrConflict(
            String raceId,
            String bib,
            com.example.starter.race.api.StartRunnerRequest request,
            RaceStartRow existing) {
        boolean sameParams = existing.raceId().equals(raceId) && existing.bib().equals(bib);
        if (!sameParams) {
            throw new ConflictException("startId 已用于不同参数的起跑: " + request.startId());
        }
        return ServiceResult.created(new com.example.starter.race.api.StartResponse(
                existing.startId(), existing.bib(), existing.startedAt()));
    }

    @Override
    @Transactional
    public ServiceResult withdrawRunner(String raceId, String bib,
            com.example.starter.race.api.WithdrawRunnerRequest request) {
        return withIdempotency(request.requestId(), "WITHDRAW_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, bib);
                    RunnerLifecycleRow lifecycle = repository.findLifecycle(raceId, bib).orElse(null);
                    if (lifecycle != null && lifecycle.status() == RunnerLifecycleStatus.WITHDRAWN) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    upsertLifecycle(raceId, bib, RunnerLifecycleStatus.WITHDRAWN,
                            lifecycle == null ? null : lifecycle.startedAt(), now);
                    // 退赛释放器材绑定。
                    repository.deleteBindingsForRunner(raceId, bib);
                    return ServiceResult.ok(new com.example.starter.race.api.WithdrawResponse(
                            bib, now));
                });
    }

    /** 新建或更新选手生命周期行。 */
    private void upsertLifecycle(
            String raceId, String bib, RunnerLifecycleStatus status, Long startedAt, long now) {
        if (repository.findLifecycle(raceId, bib).isPresent()) {
            repository.updateLifecycleStatus(raceId, bib, status, startedAt, now);
        } else {
            repository.insertLifecycle(new RunnerLifecycleRow(
                    raceId, bib, status, startedAt, now, now));
        }
    }

    /**
     * 起跑门禁：仅强制检录赛事生效。按可注入时钟校验选手存在未过期 PASS；
     * 不存在检录、PASS 已过期或最新结果为 FAIL 均抛422且不写入任何计时/起跑数据。
     */
    private void enforceInspectionGate(String raceId, String bib) {
        InspectionConfigRow config = repository.findInspectionConfig(raceId).orElse(null);
        if (config == null || !config.inspectionRequired()) {
            return;
        }
        EquipmentInspectionRow latest = repository.findLatestInspection(raceId, bib).orElse(null);
        if (latest == null) {
            throw new UnprocessableEntityException("强制检录赛事要求有效PASS检录，当前无检录记录: " + bib);
        }
        if (latest.result() == InspectionResult.FAIL) {
            throw new UnprocessableEntityException("最新检录结果为FAIL，阻断起跑: " + bib);
        }
        long now = clock.millis();
        if (latest.validUntil() == null || latest.validUntil() <= now) {
            throw new UnprocessableEntityException("PASS检录已过期，阻断起跑: " + bib);
        }
    }


    @Override
    @Transactional(readOnly = true)
    public com.example.starter.race.api.InspectionHistoryResponse getInspectionHistory(
            String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        List<com.example.starter.race.api.InspectionHistoryEntryResponse> entries =
                repository.findInspectionsForRunner(raceId, bib).stream()
                        .map(row -> new com.example.starter.race.api.InspectionHistoryEntryResponse(
                                row.inspectionId(), row.equipmentSerial(), row.result(),
                                row.result() == InspectionResult.PASS ? row.validMinutes() : null,
                                row.inspectedAt(), row.validUntil()))
                        .toList();
        return new com.example.starter.race.api.InspectionHistoryResponse(raceId, bib, entries);
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.starter.race.api.InspectionStatusResponse getInspectionStatus(
            String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        InspectionConfigRow config = repository.findInspectionConfig(raceId).orElse(null);
        boolean required = config != null && config.inspectionRequired();
        long now = clock.millis();
        EquipmentInspectionRow latest = repository.findLatestInspection(raceId, bib).orElse(null);
        if (latest == null) {
            return new com.example.starter.race.api.InspectionStatusResponse(
                    raceId, bib, required, null, null, null, null, false, now);
        }
        boolean valid = latest.result() == InspectionResult.PASS
                && latest.validUntil() != null && latest.validUntil() > now;
        return new com.example.starter.race.api.InspectionStatusResponse(
                raceId, bib, required, latest.result(), latest.equipmentSerial(),
                latest.inspectedAt(), latest.validUntil(), valid, now);
    }

    @Override
    @Transactional(readOnly = true)
    public com.example.starter.race.api.EquipmentBindingsResponse getEquipmentBindings(
            String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<com.example.starter.race.api.EquipmentBindingEntryResponse> entries =
                repository.findBindings(raceId).stream()
                        .map(row -> new com.example.starter.race.api.EquipmentBindingEntryResponse(
                                row.equipmentSerial(), row.bib(), row.inspectionId(), row.boundAt()))
                        .toList();
        return new com.example.starter.race.api.EquipmentBindingsResponse(raceId, entries);
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
