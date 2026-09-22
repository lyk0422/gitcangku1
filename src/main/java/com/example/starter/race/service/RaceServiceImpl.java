package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointConfigResponse;
import com.example.starter.race.api.CheckpointInfo;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RecordSplitRequest;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerSplitsResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.SplitDetailResponse;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.SnapshotEntryRow;
import com.example.starter.race.persistence.SnapshotRow;
import com.example.starter.race.persistence.SnapshotSplitRow;
import com.example.starter.race.persistence.SplitTimeRow;
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
import java.util.LinkedHashMap;
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
    /** 单赛事检查点数量上界。 */
    private static final int MAX_CHECKPOINTS = 20;
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
                    requireRunner(raceId, request.bib());
                    validateFinishTime(request.finishTimeMs(), false);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.updateRunnerTiming(
                            raceId, request.bib(), request.finishTimeMs(), now);
                    if (updated == 0) {
                        throw new NotFoundException("选手不存在: " + request.bib());
                    }
                    RunnerRow runner = repository.findRunner(raceId, request.bib()).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toRunnerResponse(runner));
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
    public ServiceResult sealRace(String raceId, SealRaceRequest request) {
        return withIdempotency(request.requestId(), "SEAL_RACE",
                orderedParams(
                        "raceId", raceId,
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = repository.findRace(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() == RaceStatus.SEALED) {
                        throw new ConflictException("赛事已封榜: " + raceId);
                    }
                    List<RunnerRow> runners = repository.findRunners(raceId);
                    List<PenaltyRow> penalties = repository.findPenalties(raceId);
                    SplitView splitView = loadSplitView(raceId, runners);
                    List<ResultEntry> entries = ResultCalculator.compute(
                            runners, penalties, splitView.missingCheckpointBibs());

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
                                order));
                    }
                    repository.insertSnapshot(new SnapshotRow(raceId, newVersion, now,
                            snapshotEntries));
                    // 同一事务内固化每名选手的分段明细与缺失检查点（elapsed_ms 为 NULL 表示漏点）
                    if (splitView.configured()) {
                        List<SnapshotSplitRow> snapshotSplits = new ArrayList<>();
                        for (RunnerRow runner : runners) {
                            for (SplitDetailResponse detail
                                    : splitView.splitsByBib().get(runner.bib())) {
                                snapshotSplits.add(new SnapshotSplitRow(
                                        raceId, runner.bib(), detail.checkpointCode(),
                                        detail.seq(), detail.elapsedMillis()));
                            }
                        }
                        repository.insertSnapshotSplits(snapshotSplits);
                    }
                    return ServiceResult.ok(new StandingResponse(
                            raceId, newVersion, RaceStatus.SEALED, now,
                            entries.stream()
                                    .map(entry -> ResponseMapper.toEntryResponse(
                                            entry, splitView.splitsOf(entry.bib())))
                                    .toList()));
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
            return ResponseMapper.snapshotStanding(
                    snapshot, snapshotSplitsByBib(raceId));
        }
        List<RunnerRow> runners = repository.findRunners(raceId);
        SplitView splitView = loadSplitView(raceId, runners);
        return ResponseMapper.liveStanding(
                race,
                runners,
                repository.findPenalties(raceId),
                splitView.missingCheckpointBibs(),
                splitView.configured() ? splitView.splitsByBib() : null);
    }

    @Override
    @Transactional(readOnly = true)
    public StandingResponse getSnapshot(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        SnapshotRow snapshot = repository.findSnapshot(raceId)
                .orElseThrow(() -> new NotFoundException("赛事尚未封榜: " + raceId));
        return ResponseMapper.snapshotStanding(snapshot, snapshotSplitsByBib(raceId));
    }

    @Override
    @Transactional
    public ServiceResult configureCheckpoints(String raceId, ConfigureCheckpointsRequest request) {
        return withIdempotency(request.requestId(), "CONFIGURE_CHECKPOINTS",
                orderedParams(
                        "raceId", raceId,
                        "checkpointCodes", request.checkpointCodes(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    List<String> codes = request.checkpointCodes();
                    if (codes == null || codes.isEmpty() || codes.size() > MAX_CHECKPOINTS) {
                        throw new BadRequestException("检查点数量必须在 1~20 之间");
                    }
                    for (String code : codes) {
                        if (code == null || code.isBlank() || code.length() > 64) {
                            throw new BadRequestException("checkpointCode 不能为空且长度不超过64");
                        }
                    }
                    if (new HashSet<>(codes).size() != codes.size()) {
                        throw new BadRequestException("checkpointCode 在赛事内必须唯一");
                    }
                    if (!repository.findCheckpoints(raceId).isEmpty()) {
                        throw new ConflictException("检查点已配置，不可修改: " + raceId);
                    }
                    if (repository.countSplitsForRace(raceId) > 0) {
                        throw new ConflictException("已存在分段记录，禁止配置检查点: " + raceId);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    repository.insertCheckpoints(raceId, codes, now);
                    List<CheckpointInfo> checkpoints = new ArrayList<>(codes.size());
                    for (int index = 0; index < codes.size(); index++) {
                        checkpoints.add(new CheckpointInfo(codes.get(index), index + 1));
                    }
                    RaceRow refreshed = repository.findRace(raceId).orElseThrow();
                    return ServiceResult.created(new CheckpointConfigResponse(
                            raceId, refreshed.version(), checkpoints));
                });
    }

    @Override
    @Transactional
    public ServiceResult recordSplit(String raceId, String bib, RecordSplitRequest request) {
        return withIdempotency(request.requestId(), "RECORD_SPLIT",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "checkpointCode", request.checkpointCode(),
                        "elapsedMillis", request.elapsedMillis(),
                        "expectedVersion", request.expectedVersion(),
                        "timingId", request.timingId()),
                () -> {
                    // timingId 业务幂等先于版本校验：同参重放原结果，异参409
                    Optional<SplitTimeRow> existing =
                            repository.findSplitByTimingId(request.timingId());
                    if (existing.isPresent()) {
                        SplitTimeRow row = existing.get();
                        if (row.raceId().equals(raceId) && row.bib().equals(bib)
                                && row.checkpointCode().equals(request.checkpointCode())
                                && request.elapsedMillis() != null
                                && row.elapsedMs() == request.elapsedMillis()) {
                            return ServiceResult.created(ResponseMapper.toSplitTimeResponse(row));
                        }
                        throw new ConflictException(
                                "timingId 已用于不同参数的分段记录: " + request.timingId());
                    }
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    CheckpointRow checkpoint = repository
                            .findCheckpoint(raceId, request.checkpointCode())
                            .orElseThrow(() -> new NotFoundException(
                                    "检查点不存在: " + request.checkpointCode()));
                    if (request.elapsedMillis() == null) {
                        throw new BadRequestException("分段记录必须携带 elapsedMillis");
                    }
                    long elapsedMs = request.elapsedMillis();
                    if (elapsedMs < 1 || elapsedMs > MAX_FINISH_TIME_MS) {
                        throw new BadRequestException("分段耗时毫秒数必须在 1~86400000 之间");
                    }
                    if (runner.finishTimeMs() != null && elapsedMs >= runner.finishTimeMs()) {
                        throw new UnprocessableException(
                                "分段耗时必须小于该选手原始完赛耗时: " + bib);
                    }
                    if (repository.findSplit(raceId, bib, request.checkpointCode()).isPresent()) {
                        throw new ConflictException(
                                "该选手在此检查点已有分段记录: " + request.checkpointCode());
                    }
                    validateAdjacentSplits(raceId, bib, checkpoint, elapsedMs);
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertSplit(request.timingId(), raceId, bib,
                                request.checkpointCode(), elapsedMs, now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("分段记录唯一键冲突: " + request.timingId());
                    }
                    SplitTimeRow row = repository.findSplitByTimingId(request.timingId())
                            .orElseThrow();
                    return ServiceResult.created(ResponseMapper.toSplitTimeResponse(row));
                });
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerSplitsResponse getRunnerSplits(String raceId, String bib) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        requireRunner(raceId, bib);
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        Map<String, Long> elapsedByCode = new HashMap<>();
        for (SplitTimeRow split : repository.findSplitsForRunner(raceId, bib)) {
            elapsedByCode.put(split.checkpointCode(), split.elapsedMs());
        }
        List<SplitDetailResponse> splits = new ArrayList<>(checkpoints.size());
        for (CheckpointRow checkpoint : checkpoints) {
            splits.add(new SplitDetailResponse(checkpoint.checkpointCode(), checkpoint.seq(),
                    elapsedByCode.get(checkpoint.checkpointCode())));
        }
        return new RunnerSplitsResponse(raceId, bib, splits);
    }

    @Override
    @Transactional(readOnly = true)
    public MissingCheckpointsResponse getMissingCheckpoints(String raceId) {
        repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        List<MissingCheckpointsResponse.RunnerMissingResponse> missingRunners = new ArrayList<>();
        if (!checkpoints.isEmpty()) {
            Map<String, Set<String>> coveredByBib = new HashMap<>();
            for (SplitTimeRow split : repository.findSplitsForRace(raceId)) {
                coveredByBib.computeIfAbsent(split.bib(), key -> new HashSet<>())
                        .add(split.checkpointCode());
            }
            for (RunnerRow runner : repository.findRunners(raceId)) {
                if (runner.finishTimeMs() == null) {
                    continue;
                }
                Set<String> covered = coveredByBib.getOrDefault(runner.bib(), Set.of());
                List<String> missing = new ArrayList<>();
                for (CheckpointRow checkpoint : checkpoints) {
                    if (!covered.contains(checkpoint.checkpointCode())) {
                        missing.add(checkpoint.checkpointCode());
                    }
                }
                if (!missing.isEmpty()) {
                    missingRunners.add(new MissingCheckpointsResponse.RunnerMissingResponse(
                            runner.bib(), missing));
                }
            }
        }
        return new MissingCheckpointsResponse(raceId, missingRunners);
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
        RaceRow race = repository.findRace(raceId)
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
     * 相邻约束校验：新记录与已存在的前后相邻记录比较，
     * 按检查点顺序耗时必须严格递增；违反抛出422且不写入。
     * 并发安全由赛事版本条件 UPDATE 串行化保证（校验后 bumpVersion 失败即409重试）。
     */
    private void validateAdjacentSplits(
            String raceId, String bib, CheckpointRow checkpoint, long elapsedMs) {
        SplitTimeRow previous = null;
        SplitTimeRow next = null;
        for (SplitTimeRow split : repository.findSplitsForRunner(raceId, bib)) {
            if (split.seq() < checkpoint.seq()) {
                previous = split;
            } else if (split.seq() > checkpoint.seq()) {
                next = split;
                break;
            }
        }
        if (previous != null && previous.elapsedMs() >= elapsedMs) {
            throw new UnprocessableException(
                    "分段耗时必须大于前一检查点记录: " + previous.checkpointCode());
        }
        if (next != null && elapsedMs >= next.elapsedMs()) {
            throw new UnprocessableException(
                    "分段耗时必须小于后一检查点记录: " + next.checkpointCode());
        }
    }

    /**
     * 加载赛事分段视图：检查点配置、漏点选手集合（已完赛但未覆盖全部检查点）、
     * 每名选手按检查点顺序排列的分段明细（缺失为 null）。未配置检查点时返回空视图。
     */
    private SplitView loadSplitView(String raceId, List<RunnerRow> runners) {
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        if (checkpoints.isEmpty()) {
            return new SplitView(List.of(), Set.of(), Map.of());
        }
        Map<String, Map<String, Long>> elapsedByBib = new HashMap<>();
        for (SplitTimeRow split : repository.findSplitsForRace(raceId)) {
            elapsedByBib.computeIfAbsent(split.bib(), key -> new HashMap<>())
                    .put(split.checkpointCode(), split.elapsedMs());
        }
        Set<String> missingCheckpointBibs = new HashSet<>();
        Map<String, List<SplitDetailResponse>> splitsByBib = new LinkedHashMap<>();
        for (RunnerRow runner : runners) {
            Map<String, Long> elapsed = elapsedByBib.getOrDefault(runner.bib(), Map.of());
            List<SplitDetailResponse> details = new ArrayList<>(checkpoints.size());
            boolean hasMissing = false;
            for (CheckpointRow checkpoint : checkpoints) {
                Long elapsedMs = elapsed.get(checkpoint.checkpointCode());
                if (elapsedMs == null) {
                    hasMissing = true;
                }
                details.add(new SplitDetailResponse(
                        checkpoint.checkpointCode(), checkpoint.seq(), elapsedMs));
            }
            splitsByBib.put(runner.bib(), details);
            if (runner.finishTimeMs() != null && hasMissing) {
                missingCheckpointBibs.add(runner.bib());
            }
        }
        return new SplitView(checkpoints, missingCheckpointBibs, splitsByBib);
    }

    /** 读取封榜快照固化的分段明细，按参赛号分组；未配置检查点的赛事返回 null（响应省略 splits）。 */
    private Map<String, List<SplitDetailResponse>> snapshotSplitsByBib(String raceId) {
        List<SnapshotSplitRow> splits = repository.findSnapshotSplits(raceId);
        if (splits.isEmpty()) {
            return null;
        }
        Map<String, List<SplitDetailResponse>> splitsByBib = new LinkedHashMap<>();
        for (SnapshotSplitRow split : splits) {
            splitsByBib.computeIfAbsent(split.bib(), key -> new ArrayList<>())
                    .add(new SplitDetailResponse(
                            split.checkpointCode(), split.seq(), split.elapsedMs()));
        }
        return splitsByBib;
    }

    /**
     * 分段视图。
     *
     * @param checkpoints           检查点配置（按顺序升序）；空表示赛事未配置检查点
     * @param missingCheckpointBibs 已完赛但未覆盖全部检查点的选手参赛号
     * @param splitsByBib           每名选手按检查点顺序排列的分段明细（缺失为 null）
     */
    private record SplitView(
            List<CheckpointRow> checkpoints,
            Set<String> missingCheckpointBibs,
            Map<String, List<SplitDetailResponse>> splitsByBib) {

        private boolean configured() {
            return !checkpoints.isEmpty();
        }

        private List<SplitDetailResponse> splitsOf(String bib) {
            return configured() ? splitsByBib.get(bib) : null;
        }
    }
}
