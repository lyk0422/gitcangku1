package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AppealOpinionRequest;
import com.example.starter.race.api.AppealResponse;
import com.example.starter.race.api.AppealsResponse;
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
import com.example.starter.race.api.SubmitAppealRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.domain.AppealRecommendation;
import com.example.starter.race.domain.AppealSecondAction;
import com.example.starter.race.domain.AppealStatus;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyAppealRow;
import com.example.starter.race.persistence.PenaltyAppealSegmentRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
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
                    if (penalty.superseded()) {
                        throw new ConflictException("处罚版本已被改判取代: " + penaltyId);
                    }
                    if (repository.findPendingAppealForPenalty(penaltyId).isPresent()) {
                        // 申诉冻结期间不得撤销处罚：冻结不删除处罚，但也不允许在裁决前变更。
                        throw new ConflictException("处罚存在待决申诉，禁止撤销: " + penaltyId);
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
                    // 新增分段判定成功后推进该选手分段版本，供申诉裁决时检测漏点判定并发。
                    repository.incrementSegmentVersion(raceId, bib);
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
                    if (!repository.findPendingAppealsForRace(raceId).isEmpty()) {
                        // 存在待决申诉时禁止封榜，必须先完成裁决。
                        throw new ConflictException("赛事存在待决申诉，禁止封榜: " + raceId);
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
                repository.findAllTimings(raceId),
                pendingAppealBibs(raceId));
    }

    /**
     * 计算赛事当前完整榜单（OPEN 实时数据），不标记申诉中，用于申诉冻结与裁决重算快照。
     * 必须在已持有 race 行锁的事务内调用，保证读到的是某个已串行化的一致版本。
     */
    private StandingResponse computeStanding(RaceRow race) {
        List<ResultEntry> entries = ResultCalculator.compute(
                repository.findRunners(race.raceId()),
                repository.findPenalties(race.raceId()),
                repository.findCheckpoints(race.raceId()),
                repository.findAllTimings(race.raceId()));
        return new StandingResponse(
                race.raceId(), race.version(), race.status(), null,
                entries.stream().map(ResponseMapper::toEntryResponse).toList());
    }

    /** 查询赛事当前存在待决申诉的选手集合，用于公开榜单“申诉中”标记。 */
    private java.util.Set<String> pendingAppealBibs(String raceId) {
        java.util.Set<String> bibs = new java.util.HashSet<>();
        for (var appeal : repository.findPendingAppealsForRace(raceId)) {
            bibs.add(appeal.bib());
        }
        return bibs;
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

    /** 申诉受理窗口：finishAt（最近计时修订时间）后30分钟（毫秒），含端点。 */
    private static final long APPEAL_WINDOW_MS = 30L * 60L * 1000L;

    @Override
    @Transactional
    public ServiceResult submitAppeal(
            String raceId, String penaltyId, SubmitAppealRequest request) {
        return withIdempotency(request.requestId(), "SUBMIT_APPEAL",
                orderedParams(
                        "raceId", raceId,
                        "penaltyId", penaltyId,
                        "appealKey", request.appealKey(),
                        "penaltyVersion", request.penaltyVersion(),
                        "reason", request.reason()),
                () -> {
                    long now = clock.millis();
                    // 先取 race 行写锁：与计时修订、处罚变更、封榜及其他裁决串行化。
                    RaceRow race = repository.findRaceForUpdate(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() == RaceStatus.SEALED) {
                        throw new ConflictException("赛事已封榜，禁止申诉: " + raceId);
                    }
                    PenaltyRow penalty = repository.findPenalty(penaltyId)
                            .orElseThrow(() -> new NotFoundException("处罚不存在: " + penaltyId));
                    if (!penalty.raceId().equals(raceId)) {
                        throw new NotFoundException("处罚不属于该赛事: " + penaltyId);
                    }
                    // 只能针对一条“已生效”的处罚：未撤销且未被改判取代。
                    if (penalty.revoked() || penalty.superseded()) {
                        throw new ConflictException("处罚当前未生效，不能申诉: " + penaltyId);
                    }
                    if (penalty.version() != request.penaltyVersion()) {
                        throw new ConflictException("处罚版本冲突: expected="
                                + request.penaltyVersion() + ", actual=" + penalty.version());
                    }
                    // 一条处罚同时只能有一个待决申诉。
                    if (repository.findPendingAppealForPenalty(penaltyId).isPresent()) {
                        throw new ConflictException("该处罚已存在待决申诉: " + penaltyId);
                    }
                    RunnerRow runner = repository.findRunner(raceId, penalty.bib())
                            .orElseThrow(() -> new NotFoundException(
                                    "选手不存在: " + penalty.bib()));
                    long finishAt = runner.updatedAt();
                    if (now > finishAt + APPEAL_WINDOW_MS) {
                        throw new UnprocessableEntityException(
                                "申诉超过 finishAt 后30分钟受理窗口");
                    }

                    // 冻结当时的分段判定（缺失检查点耗时为 null）。
                    List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
                    List<CheckpointTimingRow> timings =
                            repository.findTimingsForRunner(raceId, penalty.bib());
                    List<PenaltyAppealSegmentRow> frozenSegments = buildFrozenSegments(
                            request.appealKey(), penalty.bib(), checkpoints, timings);

                    // 冻结当时的完整榜单与该选手原始/净成绩；受理不推进榜单版本。
                    List<ResultEntry> currentEntries = ResultCalculator.compute(
                            repository.findRunners(raceId),
                            repository.findPenalties(raceId),
                            checkpoints,
                            repository.findAllTimings(raceId));
                    StandingResponse before = new StandingResponse(
                            race.raceId(), race.version(), race.status(), null,
                            currentEntries.stream().map(ResponseMapper::toEntryResponse).toList());
                    ResultEntry ownEntry = currentEntries.stream()
                            .filter(entry -> entry.bib().equals(penalty.bib()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "榜单缺少被处罚选手: " + penalty.bib()));

                    PenaltyAppealRow row = new PenaltyAppealRow(
                            request.appealKey(),
                            raceId,
                            penalty.bib(),
                            penaltyId,
                            AppealStatus.PENDING,
                            request.reason(),
                            penalty.version(),
                            runner.timingVersion(),
                            runner.segmentVersion(),
                            before.version(),
                            runner.finishTimeMs(),
                            ownEntry.penaltyMs(),
                            ownEntry.totalTimeMs(),
                            ownEntry.rank(),
                            ownEntry.status(),
                            finishAt,
                            writeJson(before),
                            null,
                            null,
                            null, null, null, null,
                            null, null, null,
                            now,
                            null);
                    try {
                        repository.insertAppeal(row);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("appealKey 已存在: " + request.appealKey());
                    }
                    repository.insertAppealSegments(frozenSegments);
                    return ServiceResult.created(loadAppealResponse(request.appealKey()));
                });
    }

    @Override
    @Transactional
    public ServiceResult submitAppealOpinion(
            String raceId, String appealKey, AppealOpinionRequest request) {
        boolean secondOpinion = request.action() != null && !request.action().isBlank();
        return withIdempotency(request.requestId(), "APPEAL_OPINION",
                orderedParams(
                        "raceId", raceId,
                        "appealKey", appealKey,
                        "stewardId", request.stewardId(),
                        "recommendation", request.recommendation(),
                        "replacementMs", request.replacementMs(),
                        "action", request.action()),
                () -> {
                    long now = clock.millis();
                    // race 行锁 + appeal 行锁：与其它写者及另一干事裁决串行化。
                    RaceRow race = repository.findRaceForUpdate(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    PenaltyAppealRow appeal = repository.findAppealForUpdate(appealKey)
                            .orElseThrow(() -> new NotFoundException("申诉不存在: " + appealKey));
                    if (!appeal.raceId().equals(raceId)) {
                        throw new NotFoundException("申诉不属于该赛事: " + appealKey);
                    }
                    if (appeal.status() != AppealStatus.PENDING) {
                        throw new ConflictException("申诉已裁决，不能再提交意见: " + appealKey);
                    }

                    if (!secondOpinion) {
                        return recordFirstOpinion(appealKey, appeal, request, now);
                    }
                    return recordSecondOpinion(race, appeal, request, now);
                });
    }

    /** 第一人提交建议；建议已存在则409，REPLACE 必须携带非负替代罚时。 */
    private ServiceResult recordFirstOpinion(
            String appealKey,
            PenaltyAppealRow appeal,
            AppealOpinionRequest request,
            long now) {
        if (appeal.firstStewardId() != null) {
            throw new ConflictException("第一人建议已提交: " + appealKey);
        }
        AppealRecommendation recommendation = parseRecommendation(request.recommendation());
        Long replacementMs = request.replacementMs();
        if (recommendation == AppealRecommendation.REPLACE) {
            if (replacementMs == null) {
                throw new BadRequestException("REPLACE 建议必须携带非负 replacementMs");
            }
        } else if (replacementMs != null) {
            throw new BadRequestException("仅 REPLACE 建议可携带 replacementMs");
        }
        repository.updateAppealFirstOpinion(
                appealKey, request.stewardId(), recommendation, replacementMs, now);
        return ServiceResult.ok(loadAppealResponse(appealKey));
    }

    /** 第二人确认/驳回；确认在一个事务内重读版本、变更处罚、重算榜单并只推进一个版本。 */
    private ServiceResult recordSecondOpinion(
            RaceRow race,
            PenaltyAppealRow appeal,
            AppealOpinionRequest request,
            long now) {
        if (appeal.firstStewardId() == null) {
            throw new ConflictException("第一人尚未提交建议，不能裁决: " + appeal.appealKey());
        }
        AppealSecondAction action = parseSecondAction(request.action());
        if (appeal.firstStewardId().equals(request.stewardId())) {
            throw new UnprocessableEntityException("两名裁决干事不得为同一人");
        }

        if (action == AppealSecondAction.REJECT) {
            // 驳回：不变更处罚、不推进榜单版本；after 榜单即受理冻结的原榜单。
            repository.completeAppealDecision(
                    appeal.appealKey(), request.stewardId(), AppealSecondAction.REJECT,
                    AppealStatus.REJECTED, null, appeal.beforeLeaderboard(), now);
            return ServiceResult.ok(loadAppealResponse(appeal.appealKey()));
        }

        // CONFIRM：回传建议必须与第一人完全相同。
        AppealRecommendation recommendation = parseRecommendation(request.recommendation());
        if (recommendation != appeal.firstRecommendation()) {
            throw new UnprocessableEntityException("确认建议与第一人建议不一致");
        }
        Long replacementMs = request.replacementMs();
        if (recommendation == AppealRecommendation.REPLACE) {
            if (replacementMs == null
                    || !replacementMs.equals(appeal.firstReplacementMs())) {
                throw new UnprocessableEntityException(
                        "确认的替代罚时必须与第一人建议完全相同");
            }
        } else if (replacementMs != null) {
            throw new UnprocessableEntityException("仅 REPLACE 建议可携带 replacementMs");
        }

        // 事务内重读处罚、计时与分段版本；任一变化或赛事已封榜则整次失败且申诉仍 PENDING。
        if (race.status() == RaceStatus.SEALED) {
            throw new ConflictException("赛事已封榜，不能裁决: " + race.raceId());
        }
        PenaltyRow penalty = repository.findPenaltyForUpdate(appeal.penaltyId())
                .orElseThrow(() -> new NotFoundException("处罚不存在: " + appeal.penaltyId()));
        RunnerRow runner = repository.findRunnerForUpdate(race.raceId(), appeal.bib())
                .orElseThrow(() -> new NotFoundException("选手不存在: " + appeal.bib()));
        if (penalty.version() != appeal.penaltyVersion()) {
            throw new ConflictException("处罚版本自受理后已变化，裁决冲突");
        }
        if (runner.timingVersion() != appeal.timingVersion()) {
            throw new ConflictException("计时版本自受理后已变化，裁决冲突");
        }
        if (runner.segmentVersion() != appeal.segmentVersion()) {
            throw new ConflictException("分段判定版本自受理后已变化，裁决冲突");
        }
        if (penalty.revoked() || penalty.superseded()) {
            throw new ConflictException("处罚状态自受理后已变化，裁决冲突");
        }

        String newPenaltyId = null;
        switch (recommendation) {
            case UPHOLD -> {
                // 维持处罚：处罚本身不变。
            }
            case REMOVE -> {
                int revoked = repository.markPenaltyRevoked(penalty.penaltyId(), now);
                if (revoked == 0) {
                    throw new ConflictException("处罚已撤销，裁决冲突: " + penalty.penaltyId());
                }
            }
            case REPLACE -> {
                newPenaltyId = "rep-" + java.util.UUID.randomUUID().toString().replace("-", "");
                int newPenaltyVersion = penalty.version() + 1;
                int superseded = repository.markPenaltySuperseded(penalty.penaltyId());
                if (superseded == 0) {
                    throw new ConflictException(
                            "处罚版本已被取代，裁决冲突: " + penalty.penaltyId());
                }
                repository.insertReplacementPenalty(
                        newPenaltyId, penalty, replacementMs, newPenaltyVersion, now);
            }
        }

        // 唯一一次榜单版本推进：条件 UPDATE 兜底，确保不会“处罚已改而榜单未重算”。
        int bumped = repository.bumpVersionIfOpen(race.raceId(), race.version());
        if (bumped == 0) {
            throw new ConflictException("版本冲突或赛事已封榜，裁决整体回滚");
        }
        RaceRow refreshedRace = repository.findRace(race.raceId()).orElseThrow();
        StandingResponse after = computeStanding(refreshedRace);

        AppealStatus finalStatus = switch (recommendation) {
            case UPHOLD -> AppealStatus.UPHELD;
            case REMOVE -> AppealStatus.REMOVED;
            case REPLACE -> AppealStatus.REPLACED;
        };
        repository.completeAppealDecision(
                appeal.appealKey(), request.stewardId(), AppealSecondAction.CONFIRM,
                finalStatus, newPenaltyId, writeJson(after), now);
        return ServiceResult.ok(loadAppealResponse(appeal.appealKey()));
    }

    @Override
    @Transactional(readOnly = true)
    public AppealResponse getAppeal(String raceId, String appealKey) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        PenaltyAppealRow appeal = repository.findAppeal(appealKey)
                .orElseThrow(() -> new NotFoundException("申诉不存在: " + appealKey));
        if (!appeal.raceId().equals(raceId)) {
            throw new NotFoundException("申诉不属于该赛事: " + appealKey);
        }
        return toAppealResponse(appeal);
    }

    @Override
    @Transactional(readOnly = true)
    public AppealsResponse getAppeals(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<AppealResponse> appeals = repository.findAppealsForRace(raceId).stream()
                .map(this::toAppealResponse)
                .toList();
        return new AppealsResponse(raceId, appeals);
    }

    /** 在写事务内组装申诉响应（读取本事务已写入的最新意见与快照）。 */
    private AppealResponse loadAppealResponse(String appealKey) {
        PenaltyAppealRow appeal = repository.findAppeal(appealKey).orElseThrow();
        return toAppealResponse(appeal);
    }

    private AppealResponse toAppealResponse(PenaltyAppealRow appeal) {
        List<PenaltyAppealSegmentRow> segments =
                repository.findAppealSegments(appeal.appealKey());
        return ResponseMapper.toAppealResponse(
                appeal,
                segments,
                readStanding(appeal.beforeLeaderboard()),
                appeal.afterLeaderboard() == null
                        ? null : readStanding(appeal.afterLeaderboard()));
    }

    private StandingResponse readStanding(String json) {
        try {
            return objectMapper.readValue(json, StandingResponse.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("解析榜单快照失败", ex);
        }
    }

    /** 构造受理冻结的分段判定：该选手 × 每个检查点一行，缺失检查点耗时为 null。 */
    private List<PenaltyAppealSegmentRow> buildFrozenSegments(
            String appealKey,
            String bib,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings) {
        Map<String, CheckpointTimingRow> byCode = new TreeMap<>();
        for (CheckpointTimingRow timing : timings) {
            byCode.put(timing.checkpointCode(), timing);
        }
        List<PenaltyAppealSegmentRow> rows = new ArrayList<>(checkpoints.size());
        for (CheckpointRow checkpoint : checkpoints) {
            CheckpointTimingRow timing = byCode.get(checkpoint.checkpointCode());
            if (timing == null) {
                rows.add(new PenaltyAppealSegmentRow(
                        appealKey, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), null, null));
            } else {
                rows.add(new PenaltyAppealSegmentRow(
                        appealKey, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), timing.elapsedMillis(), timing.timingId()));
            }
        }
        return rows;
    }

    private static AppealRecommendation parseRecommendation(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("必须提供 recommendation: UPHOLD/REMOVE/REPLACE");
        }
        try {
            return AppealRecommendation.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("未知建议类型: " + value);
        }
    }

    private static AppealSecondAction parseSecondAction(String value) {
        try {
            return AppealSecondAction.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("第二人动作只能是 CONFIRM 或 REJECT: " + value);
        }
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
