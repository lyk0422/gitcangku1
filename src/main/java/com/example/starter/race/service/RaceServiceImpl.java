package com.example.starter.race.service;

import com.example.starter.race.api.AccountResponse;
import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.AdjudicateProtestRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.ConfigureDepositRequest;
import com.example.starter.race.api.CreateAccountRequest;
import com.example.starter.race.api.CreateProtestRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.LedgerResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.ProtestResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SnapshotProtestResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.RechargeRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.LedgerType;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.ProtestDecision;
import com.example.starter.race.domain.ProtestStatus;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.AccountRow;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.LedgerRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.ProtestRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotCheckpointRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotProtestRow;
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
 *   <li>申诉保证金：创建申诉时在赛事行锁内按当前配置做“余额足够才扣减”的条件冻结，
 *       金额固化为申诉快照；裁决以申诉行锁+条件 UPDATE 保证终态只进一次，
 *       ACCEPT 全额退回、REJECT 全部没收，余额变动、流水与申诉状态同事务提交；
 *       封榜持赛事行锁检查 PENDING 申诉，未全部终态则拒绝，杜绝“未裁决申诉+已封榜”并存。</li>
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
                            race.raceId(), race.version(), race.status(),
                            race.depositFen(), race.createdAt()));
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
                    // 持赛事行锁检查：只要还有未裁决申诉，成绩就暂缓封榜。
                    // 创建申诉与封榜都先取同一行锁，二者不可能并发交错，
                    // 因此不会出现“未裁决申诉 + 已封榜成绩”同时存在。
                    if (repository.countPendingProtests(raceId) > 0) {
                        throw new ConflictException("赛事存在未裁决申诉，成绩暂缓封榜: " + raceId);
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
                    // 固化当时全部已终态申诉与裁决依据（理由、固定金额、ACCEPT/REJECT、时间）。
                    List<SnapshotProtestRow> snapshotProtests =
                            buildSnapshotProtests(raceId);
                    repository.insertSnapshot(new SnapshotRow(raceId, newVersion, now,
                            snapshotEntries, snapshotCheckpoints, snapshotProtests));
                    return ServiceResult.ok(new StandingResponse(
                            raceId, newVersion, RaceStatus.SEALED, now,
                            snapshotEntries.stream().map(ResponseMapper::toEntryResponse).toList(),
                            snapshotProtests.stream().map(ResponseMapper::toSnapshotProtestResponse).toList()));
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
    public ServiceResult createAccount(CreateAccountRequest request) {
        return withIdempotency(request.requestId(), "CREATE_ACCOUNT",
                orderedParams(
                        "accountId", request.accountId(),
                        "holderName", request.holderName()),
                () -> {
                    long now = clock.millis();
                    try {
                        repository.insertAccount(request.accountId(), request.holderName(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("账户已存在: " + request.accountId());
                    }
                    AccountRow account =
                            repository.findAccount(request.accountId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toAccountResponse(account));
                });
    }

    @Override
    @Transactional
    public ServiceResult recharge(String accountId, RechargeRequest request) {
        return withIdempotency(request.requestId(), "RECHARGE",
                orderedParams(
                        "accountId", accountId,
                        "amountFen", request.amountFen()),
                () -> {
                    long amountFen = request.amountFen();
                    if (amountFen <= 0) {
                        throw new BadRequestException("充值金额必须为正（分）");
                    }
                    // 锁账户串行化同账户并发入账/冻结，保证流水 balanceAfter 连续准确。
                    AccountRow account = repository.findAccountForUpdate(accountId)
                            .orElseThrow(() -> new NotFoundException("账户不存在: " + accountId));
                    long now = clock.millis();
                    repository.creditAccount(accountId, amountFen, now);
                    long balanceAfter = account.balanceFen() + amountFen;
                    repository.insertLedger(
                            accountId, null, LedgerType.RECHARGE, amountFen, balanceAfter, now);
                    AccountRow refreshed = repository.findAccount(accountId).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toAccountResponse(refreshed));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccount(String accountId) {
        AccountRow account = repository.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("账户不存在: " + accountId));
        return ResponseMapper.toAccountResponse(account);
    }

    @Override
    @Transactional
    public ServiceResult configureDeposit(String raceId, ConfigureDepositRequest request) {
        return withIdempotency(request.requestId(), "CONFIGURE_DEPOSIT",
                orderedParams(
                        "raceId", raceId,
                        "depositFen", request.depositFen(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    requireOpenRace(raceId, request.expectedVersion());
                    long depositFen = request.depositFen();
                    int updated = repository.configureDepositIfOpen(
                            raceId, request.expectedVersion(), depositFen);
                    if (updated == 0) {
                        RaceRow refreshed = repository.findRace(raceId).orElseThrow();
                        if (refreshed.status() == RaceStatus.SEALED) {
                            throw new ConflictException("赛事已封榜，禁止配置保证金: " + raceId);
                        }
                        throw new ConflictException("版本冲突: expected=" + request.expectedVersion()
                                + ", actual=" + refreshed.version());
                    }
                    RaceRow refreshed = repository.findRace(raceId).orElseThrow();
                    return ServiceResult.ok(new RaceResponse(
                            refreshed.raceId(), refreshed.version(), refreshed.status(),
                            refreshed.depositFen(), refreshed.createdAt()));
                });
    }

    @Override
    @Transactional
    public ServiceResult createProtest(String raceId, CreateProtestRequest request) {
        return withIdempotency(request.requestId(), "CREATE_PROTEST",
                orderedParams(
                        "raceId", raceId,
                        "protestId", request.protestId(),
                        "accountId", request.accountId(),
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    // 先取赛事行锁：与封榜、其它写者串行化，杜绝并发交错。
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    long depositFen = race.depositFen();
                    if (depositFen <= 0) {
                        // 业务失败随事务回滚，不占用 requestId、不锁成绩。
                        throw new UnprocessableEntityException(
                                "赛事未配置申诉保证金，不能创建申诉: " + raceId);
                    }
                    // 锁顺序固定为 race → account（裁决为 race → protest → account），避免死锁。
                    AccountRow account = repository.findAccountForUpdate(request.accountId())
                            .orElseThrow(() -> new NotFoundException(
                                    "账户不存在: " + request.accountId()));
                    long now = clock.millis();
                    // 条件冻结：余额不足时0行，不写申诉、不写流水、不推进版本，直接失败。
                    int frozen = repository.freezeIfSufficient(
                            request.accountId(), depositFen, now);
                    if (frozen == 0) {
                        throw new UnprocessableEntityException(
                                "账户余额不足以冻结申诉保证金: 需要=" + depositFen
                                        + "分, 可用=" + account.balanceFen() + "分");
                    }
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertProtest(new ProtestRow(
                                request.protestId(), raceId, request.accountId(),
                                request.reason(), depositFen,
                                ProtestStatus.PENDING, null, now, null));
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("申诉ID已存在: " + request.protestId());
                    }
                    // FREEZE 流水固定金额=本次快照；落账后余额=旧余额-保证金，恒非负。
                    repository.insertLedger(
                            request.accountId(), request.protestId(),
                            LedgerType.FREEZE, depositFen,
                            account.balanceFen() - depositFen, now);
                    ProtestRow saved =
                            repository.findProtest(request.protestId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toProtestResponse(saved));
                });
    }

    @Override
    @Transactional
    public ServiceResult adjudicateProtest(String protestId, AdjudicateProtestRequest request) {
        return withIdempotency(request.requestId(), "ADJUDICATE_PROTEST",
                orderedParams(
                        "protestId", protestId,
                        "decision", request.decision(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    ProtestRow existing = repository.findProtest(protestId)
                            .orElseThrow(() -> new NotFoundException("申诉不存在: " + protestId));
                    // 先锁赛事并校验版本/OPEN，再锁申诉与账户，锁顺序与创建申诉一致。
                    RaceRow race = requireOpenRace(existing.raceId(), request.expectedVersion());
                    ProtestDecision decision = parseDecision(request.decision());
                    ProtestRow protest = repository.findProtestForUpdate(protestId).orElseThrow();
                    if (protest.status() != ProtestStatus.PENDING) {
                        // 终态：重复裁决或改判一律409，下面的条件 UPDATE 也会在并发下再次兜底。
                        throw new ConflictException(
                                "申诉已裁决（终态），禁止重复裁决或改判: " + protestId);
                    }
                    AccountRow account = repository.findAccountForUpdate(protest.accountId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "申诉关联账户缺失: " + protest.accountId()));
                    long now = clock.millis();
                    ProtestStatus newStatus = decision == ProtestDecision.ACCEPT
                            ? ProtestStatus.ACCEPTED : ProtestStatus.REJECTED;
                    // 条件终态推进：只有 PENDING 能进终态，保证退款/没收最多发生一次。
                    int decided = repository.decideIfPending(
                            protestId, newStatus, decision, now);
                    if (decided == 0) {
                        throw new ConflictException(
                                "申诉已裁决（终态），禁止重复裁决或改判: " + protestId);
                    }
                    if (decision == ProtestDecision.ACCEPT) {
                        // 全额退回：余额加回与 REFUND 流水同事务提交，金额恒等于快照。
                        repository.creditAccount(account.accountId(), protest.depositFen(), now);
                        repository.insertLedger(
                                account.accountId(), protestId, LedgerType.REFUND,
                                protest.depositFen(),
                                account.balanceFen() + protest.depositFen(), now);
                    } else {
                        // 全部没收：余额不加回，仅记 FORFEIT 流水（固定金额=快照）。
                        repository.insertLedger(
                                account.accountId(), protestId, LedgerType.FORFEIT,
                                protest.depositFen(), account.balanceFen(), now);
                    }
                    bumpVersion(race, request.expectedVersion());
                    ProtestRow refreshed = repository.findProtest(protestId).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toProtestResponse(refreshed));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public ProtestResponse getProtest(String protestId) {
        ProtestRow protest = repository.findProtest(protestId)
                .orElseThrow(() -> new NotFoundException("申诉不存在: " + protestId));
        return ResponseMapper.toProtestResponse(protest);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProtestResponse> listProtests(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        return repository.findProtestsByRace(raceId).stream()
                .map(ResponseMapper::toProtestResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerResponse> getLedgers(String accountId) {
        repository.findAccount(accountId)
                .orElseThrow(() -> new NotFoundException("账户不存在: " + accountId));
        return repository.findLedgers(accountId).stream()
                .map(ResponseMapper::toLedgerResponse)
                .toList();
    }

    /**
     * 封榜时把赛事全部申诉固化为快照依据。调用前已在赛事行锁内确认无 PENDING，
     * 故每条记录 decision/decidedAt 必非空。
     */
    private List<SnapshotProtestRow> buildSnapshotProtests(String raceId) {
        return repository.findProtestsByRace(raceId).stream()
                .map(protest -> new SnapshotProtestRow(
                        raceId,
                        protest.protestId(),
                        protest.accountId(),
                        protest.reason(),
                        protest.depositFen(),
                        protest.decision(),
                        protest.createdAt(),
                        protest.decidedAt()))
                .toList();
    }

    private static ProtestDecision parseDecision(String decision) {
        try {
            return ProtestDecision.valueOf(decision);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("未知裁决结果: " + decision);
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
