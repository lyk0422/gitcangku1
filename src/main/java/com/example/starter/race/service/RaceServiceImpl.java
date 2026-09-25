package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AddTeamMemberRequest;
import com.example.starter.race.api.BatchLockRosterRequest;
import com.example.starter.race.api.BatchLockRosterResponse;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRosterRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RemoveTeamMemberRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RosterLockResponse;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTeamResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamRosterResponse;
import com.example.starter.race.api.TeamStandingResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.UnlockRosterRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TeamStatus;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RosterLockRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotTeamRow;
import com.example.starter.race.persistence.TeamMemberRow;
import com.example.starter.race.persistence.TeamRow;
import com.example.starter.race.persistence.TeamStandingRow;
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
    /** 锁定名单人数下限（含）。 */
    private static final int MIN_ROSTER_SIZE = 2;
    /** 锁定名单人数上限（含）。 */
    private static final int MAX_ROSTER_SIZE = 8;

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
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
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
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
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
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
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
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
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
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
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
                    // 同一事务内固化锁定队伍的团队快照：名单版本、个人成绩版本与团队得分。
                    List<SnapshotTeamRow> snapshotTeams = repository.findTeamStandings(raceId)
                            .stream()
                            .map(standing -> new SnapshotTeamRow(
                                    raceId, standing.teamId(), standing.rosterVersion(),
                                    newVersion, standing.memberCount(), standing.rankedCount(),
                                    standing.totalTimeMs()))
                            .toList();
                    repository.insertSnapshotTeams(snapshotTeams);
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
    public ServiceResult createTeam(String raceId, CreateTeamRequest request) {
        return withIdempotency(request.requestId(), "CREATE_TEAM",
                orderedParams(
                        "raceId", raceId,
                        "teamId", request.teamId(),
                        "captainBib", request.captainBib(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    requireRunner(raceId, request.captainBib());
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertTeam(raceId, request.teamId(), request.captainBib(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("队伍已存在: " + request.teamId());
                    }
                    return ServiceResult.created(
                            toTeamResponse(raceId, request.teamId(), request.expectedVersion() + 1));
                });
    }

    @Override
    @Transactional
    public ServiceResult addTeamMember(String raceId, String teamId, AddTeamMemberRequest request) {
        return withIdempotency(request.requestId(), "ADD_TEAM_MEMBER",
                orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "bib", request.bib(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    requireRosterEditable(team);
                    requireRunner(raceId, request.bib());
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertTeamMember(raceId, teamId, request.bib(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("参赛者已加入队伍: " + request.bib());
                    }
                    repository.touchTeam(raceId, teamId, now);
                    return ServiceResult.created(
                            toTeamResponse(raceId, teamId, request.expectedVersion() + 1));
                });
    }

    @Override
    @Transactional
    public ServiceResult removeTeamMember(String raceId, String teamId, String bib,
                                          RemoveTeamMemberRequest request) {
        return withIdempotency(request.requestId(), "REMOVE_TEAM_MEMBER",
                orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "bib", bib,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    requireRosterEditable(team);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    if (repository.deleteTeamMember(raceId, teamId, bib) == 0) {
                        throw new NotFoundException("成员不在队伍中: " + bib);
                    }
                    repository.touchTeam(raceId, teamId, now);
                    return ServiceResult.ok(
                            toTeamResponse(raceId, teamId, request.expectedVersion() + 1));
                });
    }

    @Override
    @Transactional
    public ServiceResult lockRoster(String raceId, String teamId, LockRosterRequest request) {
        List<String> members = normalizeMembers(request.members());
        // rosterKey 指纹含队长、赛事版本、队伍和规范化成员集合，作为名单锁定的幂等键。
        String rosterKey = rosterKey(
                raceId, teamId, request.captainBib(), request.expectedVersion(), members);
        return withIdempotency(rosterKey, "LOCK_ROSTER",
                orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "captainBib", request.captainBib(),
                        "expectedVersion", request.expectedVersion(),
                        "members", members),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    if (team.status() == TeamStatus.LOCKED) {
                        throw new ConflictException("队伍名单已锁定: " + teamId);
                    }
                    if (!team.captainBib().equals(request.captainBib())) {
                        throw new UnprocessableEntityException("仅队长可提交名单锁定: " + teamId);
                    }
                    validateLockMembers(raceId, teamId, members);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int rosterVersion = team.rosterVersion() + 1;
                    int raceVersion = request.expectedVersion() + 1;
                    writeRosterLock(raceId, teamId, rosterVersion, raceVersion,
                            request.captainBib(), members, now);
                    recomputeLockedTeamStandings(raceId, raceVersion);
                    return ServiceResult.created(new RosterLockResponse(
                            raceId, teamId, rosterVersion, raceVersion, members, now, rosterKey));
                });
    }

    @Override
    @Transactional
    public ServiceResult batchLockRosters(String raceId, BatchLockRosterRequest request) {
        List<TeamLockPlan> plans = request.teams().stream()
                .map(entry -> new TeamLockPlan(entry.teamId(), entry.captainBib(),
                        normalizeMembers(entry.members())))
                .toList();
        List<Map<String, Object>> teamParams = plans.stream()
                .map(plan -> {
                    Map<String, Object> map = new TreeMap<String, Object>();
                    map.put("teamId", plan.teamId());
                    map.put("captainBib", plan.captainBib());
                    map.put("members", plan.members());
                    return map;
                })
                .toList();
        return withIdempotency(request.requestId(), "BATCH_LOCK_ROSTER",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion(),
                        "teams", teamParams),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    // 先整批校验：成员不跨队、人数2~8、全部个人报名有效；任一失败整批422不写入。
                    validateBatchLock(raceId, plans);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int raceVersion = request.expectedVersion() + 1;
                    List<RosterLockResponse> locks = new ArrayList<>(plans.size());
                    for (TeamLockPlan plan : plans) {
                        TeamRow team = repository.findTeam(raceId, plan.teamId()).orElseThrow();
                        int rosterVersion = team.rosterVersion() + 1;
                        writeRosterLock(raceId, plan.teamId(), rosterVersion, raceVersion,
                                plan.captainBib(), plan.members(), now);
                        locks.add(new RosterLockResponse(
                                raceId, plan.teamId(), rosterVersion, raceVersion, plan.members(),
                                now, rosterKey(raceId, plan.teamId(), plan.captainBib(),
                                        request.expectedVersion(), plan.members())));
                    }
                    recomputeLockedTeamStandings(raceId, raceVersion);
                    return ServiceResult.created(
                            new BatchLockRosterResponse(raceId, raceVersion, locks));
                });
    }

    @Override
    @Transactional
    public ServiceResult unlockRoster(String raceId, String teamId, UnlockRosterRequest request) {
        return withIdempotency(request.requestId(), "UNLOCK_ROSTER",
                orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // 赛事已封榜时 requireOpenRace 直接返回409，禁止解锁。
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    if (team.status() != TeamStatus.LOCKED) {
                        throw new ConflictException("队伍名单未锁定: " + teamId);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    // 旧锁定快照仅置解锁标记与原因，不删除；重锁时生成新名单版本。
                    int updated = repository.markRosterLockUnlocked(
                            raceId, teamId, team.rosterVersion(), request.reason(), now);
                    if (updated == 0) {
                        throw new ConflictException("队伍名单未锁定: " + teamId);
                    }
                    repository.updateTeamRosterState(
                            raceId, teamId, TeamStatus.OPEN, team.rosterVersion(), now);
                    repository.deleteTeamStanding(raceId, teamId);
                    recomputeLockedTeamStandings(raceId, request.expectedVersion() + 1);
                    return ServiceResult.ok(
                            toTeamResponse(raceId, teamId, request.expectedVersion() + 1));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public TeamRosterResponse getTeamRoster(String raceId, String teamId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        TeamRow team = requireTeam(raceId, teamId);
        List<String> members = repository.findTeamMembers(raceId, teamId).stream()
                .map(TeamMemberRow::bib)
                .toList();
        List<TeamRosterResponse.RosterLockInfo> locks = repository.findRosterLocks(raceId, teamId)
                .stream()
                .map(lock -> new TeamRosterResponse.RosterLockInfo(
                        lock.rosterVersion(), lock.raceVersion(), lock.lockedBy(), lock.lockedAt(),
                        repository.findRosterLockMembers(raceId, teamId, lock.rosterVersion()),
                        lock.unlocked(), lock.unlockReason(), lock.unlockedAt()))
                .toList();
        return new TeamRosterResponse(raceId, teamId, team.captainBib(), team.status(),
                team.rosterVersion(), members, locks);
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerTeamResponse getRunnerTeam(String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        Optional<TeamMemberRow> membership = repository.findMembership(raceId, bib);
        if (membership.isEmpty()) {
            return new RunnerTeamResponse(raceId, bib, null, null, null);
        }
        TeamRow team = repository.findTeam(raceId, membership.get().teamId()).orElseThrow();
        return new RunnerTeamResponse(
                raceId, bib, team.teamId(), team.status(), team.rosterVersion());
    }

    @Override
    @Transactional(readOnly = true)
    public TeamStandingsResponse getTeamStandings(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<TeamStandingResponse> teams;
        if (race.status() == RaceStatus.SEALED) {
            // 封榜后返回固化的团队快照（名单版本、个人成绩版本与团队得分）。
            teams = repository.findSnapshotTeams(raceId).stream()
                    .map(row -> new TeamStandingResponse(
                            row.raceId(), row.teamId(), row.rosterVersion(), row.raceVersion(),
                            row.memberCount(), row.rankedCount(), row.totalTimeMs()))
                    .toList();
        } else {
            teams = repository.findTeamStandings(raceId).stream()
                    .map(row -> new TeamStandingResponse(
                            row.raceId(), row.teamId(), row.rosterVersion(), row.raceVersion(),
                            row.memberCount(), row.rankedCount(), row.totalTimeMs()))
                    .toList();
        }
        return new TeamStandingsResponse(raceId, race.version(), race.status(), teams);
    }

    /**
     * 以锁定名单和当前赛事版本重算全部已锁定队伍的团队得分：
     * 团队得分=锁定名单中全部 RANKED 成员总耗时之和；存在未排名成员时得分为 null（不完整）。
     * 无锁定队伍时为空操作。
     */
    private void recomputeLockedTeamStandings(String raceId, int raceVersion) {
        List<TeamRow> lockedTeams = repository.findLockedTeams(raceId);
        if (lockedTeams.isEmpty()) {
            return;
        }
        List<ResultEntry> entries = ResultCalculator.compute(
                repository.findRunners(raceId),
                repository.findPenalties(raceId),
                repository.findCheckpoints(raceId),
                repository.findAllTimings(raceId));
        Map<String, ResultEntry> entryByBib = new HashMap<>();
        for (ResultEntry entry : entries) {
            entryByBib.put(entry.bib(), entry);
        }
        long now = clock.millis();
        for (TeamRow team : lockedTeams) {
            List<String> members = repository.findRosterLockMembers(
                    raceId, team.teamId(), team.rosterVersion());
            long totalTimeMs = 0L;
            int rankedCount = 0;
            boolean complete = true;
            for (String bib : members) {
                ResultEntry entry = entryByBib.get(bib);
                if (entry == null || entry.status() != EntryStatus.RANKED
                        || entry.totalTimeMs() == null) {
                    complete = false;
                } else {
                    rankedCount++;
                    totalTimeMs += entry.totalTimeMs();
                }
            }
            repository.upsertTeamStanding(new TeamStandingRow(
                    raceId, team.teamId(), team.rosterVersion(), raceVersion,
                    members.size(), rankedCount, complete ? totalTimeMs : null, now));
        }
    }

    /** 写入一次名单锁定：快照头、成员快照、当前名单重建与队伍状态推进。 */
    private void writeRosterLock(String raceId, String teamId, int rosterVersion, int raceVersion,
                                 String captainBib, List<String> members, long now) {
        repository.insertRosterLock(new RosterLockRow(raceId, teamId, rosterVersion,
                raceVersion, captainBib, now, false, null, null));
        repository.insertRosterLockMembers(raceId, teamId, rosterVersion, members);
        repository.deleteTeamMembers(raceId, teamId);
        for (String bib : members) {
            repository.insertTeamMember(raceId, teamId, bib, now);
        }
        repository.updateTeamRosterState(raceId, teamId, TeamStatus.LOCKED, rosterVersion, now);
    }

    /** 批量锁定整批校验：队伍存在且未锁定、队长一致、人数与报名有效、成员不跨队。 */
    private void validateBatchLock(String raceId, List<TeamLockPlan> plans) {
        Set<String> seenTeamIds = new HashSet<>();
        Map<String, String> memberToTeam = new HashMap<>();
        for (TeamLockPlan plan : plans) {
            if (!seenTeamIds.add(plan.teamId())) {
                throw new UnprocessableEntityException("批量锁定中队伍重复: " + plan.teamId());
            }
            TeamRow team = requireTeam(raceId, plan.teamId());
            if (team.status() == TeamStatus.LOCKED) {
                throw new ConflictException("队伍名单已锁定: " + plan.teamId());
            }
            if (!team.captainBib().equals(plan.captainBib())) {
                throw new UnprocessableEntityException("仅队长可提交名单锁定: " + plan.teamId());
            }
            validateLockMembers(raceId, plan.teamId(), plan.members());
            for (String bib : plan.members()) {
                String owner = memberToTeam.putIfAbsent(bib, plan.teamId());
                if (owner != null) {
                    throw new UnprocessableEntityException(
                            "批量锁定中成员跨队: " + bib + " 同时属于 " + owner + " 与 " + plan.teamId());
                }
            }
        }
    }

    /** 校验锁定名单：人数2~8、全部成员具有有效个人报名、未加入其他队伍。 */
    private void validateLockMembers(String raceId, String teamId, List<String> members) {
        if (members.size() < MIN_ROSTER_SIZE || members.size() > MAX_ROSTER_SIZE) {
            throw new UnprocessableEntityException(
                    "锁定名单人数必须在 2~8 之间: " + members.size());
        }
        for (String bib : members) {
            if (repository.findRunner(raceId, bib).isEmpty()) {
                throw new UnprocessableEntityException("成员无有效个人报名: " + bib);
            }
            Optional<TeamMemberRow> membership = repository.findMembership(raceId, bib);
            if (membership.isPresent() && !membership.get().teamId().equals(teamId)) {
                throw new UnprocessableEntityException("成员已属于其他队伍: " + bib);
            }
        }
    }

    /** 规范化成员集合：去空白、去重、按字典序排序，使指纹与请求顺序无关。 */
    private static List<String> normalizeMembers(List<String> members) {
        return members.stream().map(String::trim).distinct().sorted().toList();
    }

    /** 计算名单锁定幂等指纹：队长+赛事版本+队伍+规范化成员集合的摘要。 */
    private String rosterKey(String raceId, String teamId, String captainBib,
                             int expectedVersion, List<String> members) {
        return "roster-lock:" + digest("LOCK_ROSTER", orderedParams(
                "raceId", raceId,
                "teamId", teamId,
                "captainBib", captainBib,
                "raceVersion", expectedVersion,
                "members", members));
    }

    private TeamRow requireTeam(String raceId, String teamId) {
        return repository.findTeam(raceId, teamId)
                .orElseThrow(() -> new NotFoundException("队伍不存在: " + teamId));
    }

    private static void requireRosterEditable(TeamRow team) {
        if (team.status() == TeamStatus.LOCKED) {
            throw new ConflictException("队伍名单已锁定，禁止增删成员: " + team.teamId());
        }
    }

    private TeamResponse toTeamResponse(String raceId, String teamId, int raceVersion) {
        TeamRow team = repository.findTeam(raceId, teamId).orElseThrow();
        List<String> members = repository.findTeamMembers(raceId, teamId).stream()
                .map(TeamMemberRow::bib)
                .toList();
        return new TeamResponse(raceId, teamId, team.captainBib(), team.status(),
                team.rosterVersion(), raceVersion, members);
    }

    /** 批量锁定中一支队伍的锁定计划（成员已规范化）。 */
    private record TeamLockPlan(String teamId, String captainBib, List<String> members) {
    }
}
