package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AddTeamMemberRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.CreateTeamRequest;
import com.example.starter.race.api.LockRostersRequest;
import com.example.starter.race.api.LockRostersResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.RemoveTeamMemberRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RosterResponse;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTeamResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.TeamResponse;
import com.example.starter.race.api.TeamSnapshotResponse;
import com.example.starter.race.api.TeamStandingsResponse;
import com.example.starter.race.api.UnlockRosterRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.domain.TeamLockStatus;
import com.example.starter.race.domain.TeamScoreCalculator;
import com.example.starter.race.domain.TeamStanding;
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
import com.example.starter.race.persistence.TeamMemberRow;
import com.example.starter.race.persistence.TeamRow;
import com.example.starter.race.persistence.TeamSealSnapshotRow;
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
    /** 锁定名单人数下界（含）。 */
    private static final int MIN_TEAM_SIZE = 2;
    /** 锁定名单人数上界（含）。 */
    private static final int MAX_TEAM_SIZE = 8;

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
                    // 同一封榜事务内固化锁定队伍的名单版本、个人成绩版本与团队得分。
                    List<TeamSealSnapshotRow> teamSnapshots =
                            buildTeamSealSnapshots(raceId, entries, newVersion, now);
                    if (!teamSnapshots.isEmpty()) {
                        repository.insertTeamSealSnapshots(teamSnapshots);
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
                    if (repository.findTeam(raceId, request.teamId()).isPresent()) {
                        throw new ConflictException("队伍已存在: " + request.teamId());
                    }
                    repository.findMembership(raceId, request.captainBib())
                            .ifPresent(membership -> {
                                throw new ConflictException(
                                        "参赛者已加入队伍: " + membership.teamId());
                            });
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertTeam(raceId, request.teamId(), request.captainBib(), now);
                        repository.insertTeamMember(
                                raceId, request.teamId(), request.captainBib(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("队伍已存在: " + request.teamId());
                    }
                    TeamRow team = repository.findTeam(raceId, request.teamId()).orElseThrow();
                    return ServiceResult.created(new TeamResponse(
                            raceId, team.teamId(), team.captainBib(), team.status(),
                            team.rosterVersion(), request.expectedVersion() + 1,
                            team.createdAt()));
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
                    requireUnlocked(team);
                    requireRunner(raceId, request.bib());
                    repository.findMembership(raceId, request.bib())
                            .ifPresent(membership -> {
                                throw new ConflictException(
                                        "参赛者已加入队伍: " + membership.teamId());
                            });
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertTeamMember(raceId, teamId, request.bib(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("参赛者已加入队伍: " + request.bib());
                    }
                    return ServiceResult.created(buildRosterResponse(raceId, teamId));
                });
    }

    @Override
    @Transactional
    public ServiceResult removeTeamMember(
            String raceId, String teamId, String bib, RemoveTeamMemberRequest request) {
        return withIdempotency(request.requestId(), "REMOVE_TEAM_MEMBER",
                orderedParams(
                        "raceId", raceId,
                        "teamId", teamId,
                        "bib", bib,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    requireUnlocked(team);
                    if (team.captainBib().equals(bib)) {
                        throw new UnprocessableEntityException("队长不可从名单中移除: " + bib);
                    }
                    bumpVersion(race, request.expectedVersion());
                    int deleted = repository.deleteTeamMember(raceId, teamId, bib);
                    if (deleted == 0) {
                        throw new NotFoundException("成员不在队伍中: " + bib);
                    }
                    return ServiceResult.ok(buildRosterResponse(raceId, teamId));
                });
    }

    @Override
    @Transactional
    public ServiceResult lockRosters(String raceId, LockRostersRequest request) {
        // rosterKey 指纹：队长 + 赛事版本 + 队伍 + 规范化成员集合（排序去重，与请求顺序无关）。
        List<LockRostersRequest.TeamLockRequest> normalizedLocks = request.locks().stream()
                .sorted(java.util.Comparator.comparing(LockRostersRequest.TeamLockRequest::teamId))
                .toList();
        return withIdempotency(request.requestId(), "LOCK_ROSTERS",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion(),
                        "locks", normalizedLocks.stream()
                                .map(lock -> lock.teamId() + ":" + lock.captainBib() + ":"
                                        + String.join(",", normalizedMembers(lock.members())))
                                .toList()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    validateLockBatch(raceId, normalizedLocks);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int newVersion = request.expectedVersion() + 1;
                    List<LockRostersResponse.RosterLockItem> items = new ArrayList<>();
                    for (LockRostersRequest.TeamLockRequest lock : normalizedLocks) {
                        TeamRow team = repository.findTeam(raceId, lock.teamId()).orElseThrow();
                        int rosterVersion = team.rosterVersion() + 1;
                        List<String> members = normalizedMembers(lock.members());
                        // 一事务内写入全部锁定快照并同步当前名单。
                        repository.insertRosterLock(new RosterLockRow(
                                raceId, lock.teamId(), rosterVersion, lock.captainBib(),
                                newVersion, now, TeamLockStatus.LOCKED, null, null));
                        repository.insertRosterLockMembers(
                                raceId, lock.teamId(), rosterVersion, members);
                        repository.deleteTeamMembers(raceId, lock.teamId());
                        for (String bib : members) {
                            repository.insertTeamMember(raceId, lock.teamId(), bib, now);
                        }
                        repository.updateTeamLockState(
                                raceId, lock.teamId(), TeamLockStatus.LOCKED, rosterVersion, now);
                        items.add(new LockRostersResponse.RosterLockItem(
                                lock.teamId(), rosterVersion, lock.captainBib(), members,
                                newVersion, now));
                    }
                    return ServiceResult.created(
                            new LockRostersResponse(raceId, newVersion, items));
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
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    TeamRow team = requireTeam(raceId, teamId);
                    RosterLockRow activeLock = repository.findActiveLock(raceId, teamId)
                            .orElseThrow(() -> new ConflictException(
                                    "队伍未处于锁定状态: " + teamId));
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    // 解锁不删除旧锁定快照，仅标记状态并记录原因；重锁生成新版本。
                    int updated = repository.markLockUnlocked(
                            raceId, teamId, activeLock.rosterVersion(), request.reason(), now);
                    if (updated == 0) {
                        throw new ConflictException("队伍未处于锁定状态: " + teamId);
                    }
                    repository.updateTeamLockState(
                            raceId, teamId, TeamLockStatus.UNLOCKED, team.rosterVersion(), now);
                    return ServiceResult.ok(buildRosterResponse(raceId, teamId));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public RosterResponse getRoster(String raceId, String teamId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireTeam(raceId, teamId);
        return buildRosterResponse(raceId, teamId);
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
                raceId, bib, team.teamId(), team.rosterVersion(),
                team.status() == TeamLockStatus.LOCKED);
    }

    @Override
    @Transactional(readOnly = true)
    public TeamStandingsResponse getTeamStandings(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        if (race.status() == RaceStatus.SEALED) {
            SnapshotRow snapshot = repository.findSnapshot(raceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "赛事已封榜但缺少快照: " + raceId));
            return new TeamStandingsResponse(
                    raceId, snapshot.version(), RaceStatus.SEALED, snapshot.sealedAt(),
                    repository.findTeamSealSnapshots(raceId).stream()
                            .map(ResponseMapper::toTeamStandingEntry)
                            .toList());
        }
        // OPEN：以锁定名单和当前赛事版本实时重算团队得分。
        List<TeamStanding> standings = computeLiveTeamStandings(raceId);
        return new TeamStandingsResponse(
                raceId, race.version(), race.status(), null,
                standings.stream().map(ResponseMapper::toTeamStandingEntry).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public TeamSnapshotResponse getTeamSnapshot(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        SnapshotRow snapshot = repository.findSnapshot(raceId)
                .orElseThrow(() -> new NotFoundException("赛事尚未封榜: " + raceId));
        return new TeamSnapshotResponse(
                raceId, snapshot.version(), snapshot.sealedAt(),
                repository.findTeamSealSnapshots(raceId).stream()
                        .map(ResponseMapper::toTeamSnapshotEntry)
                        .toList());
    }

    private TeamRow requireTeam(String raceId, String teamId) {
        return repository.findTeam(raceId, teamId)
                .orElseThrow(() -> new NotFoundException("队伍不存在: " + teamId));
    }

    private static void requireUnlocked(TeamRow team) {
        if (team.status() == TeamLockStatus.LOCKED) {
            throw new ConflictException("队伍名单已锁定，禁止普通增删成员: " + team.teamId());
        }
    }

    /** 规范化成员集合：去重后按参赛号字典序排列。 */
    private static List<String> normalizedMembers(List<String> members) {
        return members.stream().distinct().sorted().toList();
    }

    /**
     * 批量锁定校验：队伍存在且未锁定、队长一致且在名单中、人数2~8、成员不重复不跨队、
     * 全部成员具有效个人报名；任一失败抛出异常整批回滚（422/404/409）。
     */
    private void validateLockBatch(
            String raceId, List<LockRostersRequest.TeamLockRequest> locks) {
        java.util.Set<String> seenTeamIds = new java.util.HashSet<>();
        java.util.Set<String> registeredBibs = new java.util.HashSet<>();
        for (RunnerRow runner : repository.findRunners(raceId)) {
            registeredBibs.add(runner.bib());
        }
        Map<String, String> membershipByBib = new java.util.HashMap<>();
        for (TeamMemberRow member : repository.findAllTeamMembers(raceId)) {
            membershipByBib.put(member.bib(), member.teamId());
        }
        java.util.Set<String> batchClaimedBibs = new java.util.HashSet<>();
        for (LockRostersRequest.TeamLockRequest lock : locks) {
            if (!seenTeamIds.add(lock.teamId())) {
                throw new UnprocessableEntityException(
                        "批量锁定中队伍重复: " + lock.teamId());
            }
            TeamRow team = requireTeam(raceId, lock.teamId());
            if (team.status() == TeamLockStatus.LOCKED) {
                throw new ConflictException("队伍已锁定，须先解锁再重新锁定: " + lock.teamId());
            }
            if (!team.captainBib().equals(lock.captainBib())) {
                throw new UnprocessableEntityException(
                        "队长与队伍登记不一致: " + lock.teamId());
            }
            List<String> members = lock.members();
            if (members.size() != normalizedMembers(members).size()) {
                throw new UnprocessableEntityException(
                        "名单成员重复: " + lock.teamId());
            }
            if (members.size() < MIN_TEAM_SIZE || members.size() > MAX_TEAM_SIZE) {
                throw new UnprocessableEntityException(
                        "队伍人数必须在 2~8 之间: " + lock.teamId());
            }
            if (!members.contains(lock.captainBib())) {
                throw new UnprocessableEntityException(
                        "队长必须在锁定名单中: " + lock.teamId());
            }
            for (String bib : members) {
                if (!registeredBibs.contains(bib)) {
                    throw new UnprocessableEntityException(
                            "成员无有效个人报名: " + bib);
                }
                if (!batchClaimedBibs.add(bib)) {
                    throw new UnprocessableEntityException(
                            "成员在批量锁定中跨队: " + bib);
                }
                String existingTeam = membershipByBib.get(bib);
                if (existingTeam != null && !existingTeam.equals(lock.teamId())) {
                    throw new UnprocessableEntityException(
                            "成员已属于其他队伍: " + bib);
                }
            }
        }
    }

    /** 组装队伍名单查询响应：当前名单、名单版本与最近锁定/解锁信息。 */
    private RosterResponse buildRosterResponse(String raceId, String teamId) {
        RaceRow race = repository.findRace(raceId).orElseThrow();
        TeamRow team = repository.findTeam(raceId, teamId).orElseThrow();
        List<String> members = repository.findTeamMembers(raceId, teamId).stream()
                .map(TeamMemberRow::bib)
                .toList();
        Long lockedAt = null;
        Integer lockRaceVersion = null;
        Long unlockedAt = null;
        String unlockReason = null;
        Optional<RosterLockRow> latestLock = repository.findLatestLock(raceId, teamId);
        if (latestLock.isPresent()) {
            RosterLockRow lock = latestLock.get();
            if (lock.status() == TeamLockStatus.LOCKED) {
                lockedAt = lock.lockedAt();
                lockRaceVersion = lock.raceVersion();
            } else {
                unlockedAt = lock.unlockedAt();
                unlockReason = lock.unlockReason();
            }
        }
        return new RosterResponse(
                raceId, teamId, team.captainBib(), team.status(), team.rosterVersion(),
                members, race.version(), lockedAt, lockRaceVersion, unlockedAt, unlockReason);
    }

    /** 以生效锁定名单与当前赛事版本的个人成绩实时计算团队得分。 */
    private List<TeamStanding> computeLiveTeamStandings(String raceId) {
        List<TeamScoreCalculator.LockedTeam> lockedTeams = new ArrayList<>();
        for (RosterLockRow lock : repository.findActiveLocks(raceId)) {
            lockedTeams.add(new TeamScoreCalculator.LockedTeam(
                    lock.teamId(), lock.rosterVersion(),
                    repository.findLockMembers(raceId, lock.teamId(), lock.rosterVersion())));
        }
        List<ResultEntry> entries = ResultCalculator.compute(
                repository.findRunners(raceId),
                repository.findPenalties(raceId),
                repository.findCheckpoints(raceId),
                repository.findAllTimings(raceId));
        return TeamScoreCalculator.compute(lockedTeams, entries);
    }

    /** 封榜时以锁定名单与最终个人成绩构建队伍快照（名单版本、成绩版本、团队得分）。 */
    private List<TeamSealSnapshotRow> buildTeamSealSnapshots(
            String raceId, List<ResultEntry> entries, int resultVersion, long sealedAt) {
        List<TeamScoreCalculator.LockedTeam> lockedTeams = new ArrayList<>();
        for (RosterLockRow lock : repository.findActiveLocks(raceId)) {
            lockedTeams.add(new TeamScoreCalculator.LockedTeam(
                    lock.teamId(), lock.rosterVersion(),
                    repository.findLockMembers(raceId, lock.teamId(), lock.rosterVersion())));
        }
        return TeamScoreCalculator.compute(lockedTeams, entries).stream()
                .map(standing -> new TeamSealSnapshotRow(
                        raceId,
                        standing.teamId(),
                        standing.rosterVersion(),
                        resultVersion,
                        standing.teamScoreMs(),
                        standing.teamRank(),
                        standing.members().size(),
                        standing.complete(),
                        sealedAt,
                        standing.members()))
                .toList();
    }
}
