package com.example.starter.race.service;

import com.example.starter.race.api.AddPenaltyRequest;
import com.example.starter.race.api.CheckpointResponse;
import com.example.starter.race.api.CheckpointsConfigResponse;
import com.example.starter.race.api.ConfigureCheckpointsRequest;
import com.example.starter.race.api.CreateRaceRequest;
import com.example.starter.race.api.ExcludedTimingResponse;
import com.example.starter.race.api.FinishEventInHoldResponse;
import com.example.starter.race.api.MedicalHoldHistoryItem;
import com.example.starter.race.api.MedicalHoldHistoryResponse;
import com.example.starter.race.api.MissingCheckpointsResponse;
import com.example.starter.race.api.RaceResponse;
import com.example.starter.race.api.RegisterRunnerRequest;
import com.example.starter.race.api.ResumeMedicalHoldRequest;
import com.example.starter.race.api.ReviseTimeRequest;
import com.example.starter.race.api.RevokePenaltyRequest;
import com.example.starter.race.api.RunnerMedicalDiagnosisResponse;
import com.example.starter.race.api.RunnerMissingCheckpointsResponse;
import com.example.starter.race.api.RunnerTimingResponse;
import com.example.starter.race.api.SealRaceRequest;
import com.example.starter.race.api.StandingResponse;
import com.example.starter.race.api.StartMedicalHoldRequest;
import com.example.starter.race.api.SubmitTimingRequest;
import com.example.starter.race.api.WithdrawRunnerRequest;
import com.example.starter.race.domain.CheckpointRules;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import com.example.starter.race.domain.ResultCalculator;
import com.example.starter.race.domain.ResultEntry;
import com.example.starter.race.persistence.ActiveMedicalHoldRow;
import com.example.starter.race.persistence.CheckpointRow;
import com.example.starter.race.persistence.CheckpointTimingRow;
import com.example.starter.race.persistence.IdempotencyRow;
import com.example.starter.race.persistence.MedicalHoldDetailRow;
import com.example.starter.race.persistence.MedicalHoldResumptionRow;
import com.example.starter.race.persistence.MedicalHoldRow;
import com.example.starter.race.persistence.PenaltyRow;
import com.example.starter.race.persistence.RaceRow;
import com.example.starter.race.persistence.RunnerFinishEventRow;
import com.example.starter.race.persistence.RunnerRow;
import com.example.starter.race.persistence.RunnerWithdrawalRow;
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
import java.util.LinkedHashMap;
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
 *   <li>暂停、恢复、分段计时、退赛与封榜全部在同一串行化顺序上裁决：
 *       分段计时插入时读取到的生效暂停即“提交时刻”的资格状态，暂停先提交则同刻后计时标 MEDICAL_HOLD，
 *       计时先提交则按当时资格正常入榜；排除结论固化在分段行上，后续暂停/恢复不追溯改写；</li>
 *   <li>封榜在同一事务内完成条件转 SEALED 与全体选手只读快照写入（含资格状态与排除原因）；</li>
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
                    if (finishTimeMs != null) {
                        repository.insertFinishEvent(
                                raceId, request.bib(), finishTimeMs, request.requestId(), now);
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
                    rejectIfWithdrawn(runner);
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
                    // 每次修订追加不可变终点计时事件，恢复暂停时据此判定区间内是否出现过终点计时。
                    repository.insertFinishEvent(
                            raceId, request.bib(), request.finishTimeMs(), request.requestId(), now);
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
                    // timingId 是第二层全局幂等键：同参重放原结果（含固化的 MEDICAL_HOLD 标记），异参409。
                    CheckpointTimingRow existingTiming =
                            repository.findTiming(request.timingId()).orElse(null);
                    if (existingTiming != null) {
                        return replayTimingOrConflict(raceId, bib, request, existingTiming);
                    }

                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    rejectIfWithdrawn(runner);
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
                    // 按提交顺序裁决：race 行锁内读到生效暂停即说明暂停先于本计时提交，
                    // 本计时保存但标 MEDICAL_HOLD；该结论之后不再随暂停恢复而改变。
                    String excludedHoldId = repository
                            .findActiveMedicalHold(raceId, bib)
                            .map(ActiveMedicalHoldRow::holdId)
                            .orElse(null);
                    bumpVersion(race, request.expectedVersion());
                    long now = clock.millis();
                    CheckpointTimingRow row = new CheckpointTimingRow(
                            request.timingId(), raceId, bib, request.checkpointCode(),
                            checkpoint.position(), elapsedMillis, excludedHoldId, now);
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
    public ServiceResult withdrawRunner(String raceId, String bib, WithdrawRunnerRequest request) {
        return withIdempotency(request.requestId(), "WITHDRAW_RUNNER",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "reason", request.reason(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    if (runner.withdrawn()) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    if (hasEffectiveDisqualification(raceId, bib)) {
                        throw new ConflictException("选手已被取消资格，不能退赛: " + bib);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    int updated = repository.markRunnerWithdrawn(raceId, bib, now);
                    if (updated == 0) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    try {
                        repository.insertWithdrawal(raceId, bib, request.reason(), now);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("选手已退赛: " + bib);
                    }
                    RunnerRow refreshed = repository.findRunner(raceId, bib).orElseThrow();
                    return ServiceResult.ok(ResponseMapper.toRunnerResponse(refreshed));
                });
    }

    @Override
    @Transactional
    public ServiceResult startMedicalHold(
            String raceId, String bib, StartMedicalHoldRequest request) {
        return withIdempotency(request.requestId(), "START_MEDICAL_HOLD",
                orderedParams(
                        "raceId", raceId,
                        "bib", bib,
                        "holdId", request.holdId(),
                        "startAt", request.startAt(),
                        "reason", request.reason(),
                        "medicalOfficer", request.medicalOfficer(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    RunnerRow runner = requireRunner(raceId, bib);
                    rejectIfWithdrawn(runner);
                    if (hasEffectiveDisqualification(raceId, bib)) {
                        throw new ConflictException("选手已被取消资格，不可登记医疗暂停: " + bib);
                    }
                    if (repository.findActiveMedicalHold(raceId, bib).isPresent()) {
                        throw new ConflictException("选手已有生效中的医疗暂停，重复开始被拒绝: " + bib);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    MedicalHoldRow hold = new MedicalHoldRow(
                            request.holdId(), raceId, bib, request.startAt(),
                            request.reason(), request.medicalOfficer(), now);
                    try {
                        repository.insertMedicalHold(hold);
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("医疗暂停ID已存在: " + request.holdId());
                    }
                    try {
                        repository.insertActiveMedicalHold(new ActiveMedicalHoldRow(
                                raceId, bib, request.holdId(), request.startAt()));
                    } catch (DuplicateKeyException ex) {
                        // 与并发开始竞争同一选手：唯一约束兜底，本事务回滚不留下半成品。
                        throw new ConflictException("选手已有生效中的医疗暂停，重复开始被拒绝: " + bib);
                    }
                    MedicalHoldRow saved =
                            repository.findMedicalHold(request.holdId()).orElseThrow();
                    return ServiceResult.created(ResponseMapper.toMedicalHoldResponse(saved));
                });
    }

    @Override
    @Transactional
    public ServiceResult resumeMedicalHold(
            String raceId, String holdId, ResumeMedicalHoldRequest request) {
        return withIdempotency(request.requestId(), "RESUME_MEDICAL_HOLD",
                orderedParams(
                        "raceId", raceId,
                        "holdId", holdId,
                        "endAt", request.endAt(),
                        "medicalOfficer", request.medicalOfficer(),
                        "fitConclusion", request.fitConclusion(),
                        "expectedVersion", request.expectedVersion()),
                () -> {
                    RaceRow race = requireOpenRace(raceId, request.expectedVersion());
                    MedicalHoldRow hold = repository.findMedicalHold(holdId)
                            .orElseThrow(() -> new NotFoundException("医疗暂停不存在: " + holdId));
                    if (!hold.raceId().equals(raceId)) {
                        throw new NotFoundException("医疗暂停不属于该赛事: " + holdId);
                    }
                    RunnerRow runner = requireRunner(hold.raceId(), hold.bib());
                    rejectIfWithdrawn(runner);
                    if (hasEffectiveDisqualification(hold.raceId(), hold.bib())) {
                        throw new ConflictException("选手已被取消资格，不可恢复医疗暂停: " + hold.bib());
                    }
                    if (repository.findResumption(holdId).isPresent()) {
                        throw new ConflictException("医疗暂停已恢复: " + holdId);
                    }
                    if (repository.findActiveMedicalHold(hold.raceId(), hold.bib()).isEmpty()) {
                        // 理论上被“开始存在+无恢复”与 active 行的一致性排除，防御性处理。
                        throw new ConflictException("医疗暂停非生效状态: " + holdId);
                    }
                    long endAt = request.endAt();
                    if (endAt <= hold.startAt()) {
                        long delta = hold.startAt() - endAt;
                        throw new BadRequestException(
                                "暂停结束时刻必须晚于开始时刻: 实际endAt=" + endAt
                                        + ", 要求endAt>startAt=" + hold.startAt()
                                        + ", 差额=" + delta + "毫秒");
                    }
                    if (request.medicalOfficer().equals(hold.startedBy())) {
                        throw new UnprocessableEntityException(
                                "恢复必须由不同于登记人的医疗角色确认适赛: 登记人="
                                        + hold.startedBy() + ", 恢复人=" + request.medicalOfficer());
                    }
                    // 暂停区间[startAt,endAt)内若已提交过终点计时，须先按既有规则裁定完赛状态。
                    List<RunnerFinishEventRow> finishEvents =
                            repository.findFinishEvents(hold.raceId(), hold.bib());
                    List<RunnerFinishEventRow> inHold = finishEvents.stream()
                            .filter(event -> event.createdAt() >= hold.startAt()
                                    && event.createdAt() < endAt)
                            .toList();
                    if (!inHold.isEmpty()) {
                        throw new UnprocessableEntityException(
                                "暂停期间存在 " + inHold.size() + " 条终点计时，"
                                        + "请先按既有规则裁定完赛状态再恢复暂停: holdId=" + holdId);
                    }
                    long now = clock.millis();
                    bumpVersion(race, request.expectedVersion());
                    try {
                        repository.insertMedicalHoldResumption(new MedicalHoldResumptionRow(
                                holdId, endAt, request.medicalOfficer(),
                                request.fitConclusion(), now));
                    } catch (DuplicateKeyException ex) {
                        throw new ConflictException("医疗暂停已恢复: " + holdId);
                    }
                    int removed = repository.deleteActiveMedicalHold(hold.raceId(), hold.bib());
                    if (removed == 0) {
                        throw new ConflictException("医疗暂停非生效状态: " + holdId);
                    }
                    MedicalHoldRow savedHold = repository.findMedicalHold(holdId).orElseThrow();
                    MedicalHoldResumptionRow savedResumption =
                            repository.findResumption(holdId).orElseThrow();
                    return ServiceResult.ok(
                            ResponseMapper.toResumptionResponse(savedHold, savedResumption));
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
                    RaceRow race = repository.findRaceForUpdate(raceId)
                            .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
                    if (race.status() == RaceStatus.SEALED) {
                        throw new ConflictException("赛事已封榜: " + raceId);
                    }
                    List<RunnerRow> runners = repository.findRunners(raceId);
                    List<PenaltyRow> penalties = repository.findPenalties(raceId);
                    List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
                    List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
                    List<MedicalHoldDetailRow> holds = repository.findMedicalHoldDetails(raceId);
                    List<ResultEntry> entries =
                            ResultCalculator.compute(runners, penalties, checkpoints, timings, holds);

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
                                entry.missingCheckpoints(),
                                entry.medicalHoldActive(),
                                entry.excludedTimingCount()));
                    }
                    // 在同一封榜事务内固化每名选手 × 每个检查点的明细，缺失行耗时为 null；
                    // 被医疗暂停排除的分段固化暂停ID与原因，封榜后不再被任何后续医疗记录改写。
                    List<SnapshotCheckpointRow> snapshotCheckpoints =
                            buildSnapshotCheckpoints(raceId, runners, checkpoints, timings, holds);
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
                repository.findMedicalHoldDetails(raceId));
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
        Map<String, String> holdReasons = holdReasonMap(repository.findMedicalHolds(raceId));
        return ResponseMapper.runnerTiming(
                race, runner, checkpoints,
                repository.findTimingsForRunner(raceId, bib), holdReasons);
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
            // 封榜后由固化明细派生缺失检查点：elapsedMillis 为 NULL 或被医疗暂停排除的行均不计覆盖。
            Map<String, List<String>> missingByBib = new TreeMap<>();
            for (SnapshotCheckpointRow detail : snapshot.checkpoints()) {
                if (detail.elapsedMillis() == null || detail.excludedHoldId() != null) {
                    missingByBib.computeIfAbsent(detail.bib(), key -> new ArrayList<>())
                            .add(detail.checkpointCode());
                }
            }
            List<RunnerRow> sealedRunners = repository.findRunners(raceId);
            List<RunnerMissingCheckpointsResponse> rows = sealedRunners.stream()
                    .map(r -> new RunnerMissingCheckpointsResponse(
                            r.bib(),
                            missingByBib.getOrDefault(r.bib(), List.of())))
                    .toList();
            return new MissingCheckpointsResponse(
                    raceId, race.version(), checkpoints.size(), rows);
        }
        runners = repository.findRunners(raceId);
        timings = repository.findAllTimings(raceId);
        // 按参赛号字典序稳定汇总；缺失=已配置但该选手尚无有效分段记录的检查点（MEDICAL_HOLD 不计覆盖）。
        Map<String, java.util.Set<String>> coveredByBib = new TreeMap<>();
        for (RunnerRow runner : runners) {
            coveredByBib.put(runner.bib(), new java.util.HashSet<>());
        }
        for (CheckpointTimingRow timing : timings) {
            if (timing.excludedHoldId() != null) {
                continue;
            }
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
    @Transactional(readOnly = true)
    public MedicalHoldHistoryResponse getMedicalHoldHistory(String raceId) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        List<MedicalHoldDetailRow> details = repository.findMedicalHoldDetails(raceId);
        Map<String, Integer> excludedCountByHold = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : repository.findAllTimings(raceId)) {
            if (timing.excludedHoldId() != null) {
                excludedCountByHold.merge(timing.excludedHoldId(), 1, Integer::sum);
            }
        }
        List<MedicalHoldHistoryItem> items = details.stream()
                .map(detail -> ResponseMapper.toHistoryItem(
                        detail, excludedCountByHold.getOrDefault(detail.holdId(), 0)))
                .toList();
        return new MedicalHoldHistoryResponse(raceId, race.version(), items);
    }

    @Override
    @Transactional(readOnly = true)
    public RunnerMedicalDiagnosisResponse getRunnerMedicalDiagnosis(String raceId, String bib) {
        RaceRow race = repository.findRace(raceId)
                .orElseThrow(() -> new NotFoundException("赛事不存在: " + raceId));
        RunnerRow runner = repository.findRunner(raceId, bib)
                .orElseThrow(() -> new NotFoundException("选手不存在: " + bib));
        List<RunnerRow> runners = repository.findRunners(raceId);
        List<PenaltyRow> penalties = repository.findPenalties(raceId);
        List<CheckpointRow> checkpoints = repository.findCheckpoints(raceId);
        List<CheckpointTimingRow> timings = repository.findAllTimings(raceId);
        List<MedicalHoldDetailRow> holds = repository.findMedicalHoldDetails(raceId);

        ResultEntry ownEntry = ResultCalculator
                .compute(runners, penalties, checkpoints, timings, holds).stream()
                .filter(entry -> entry.bib().equals(bib))
                .findFirst()
                .orElseThrow();

        List<MedicalHoldDetailRow> ownHolds = holds.stream()
                .filter(hold -> hold.bib().equals(bib))
                .toList();
        String activeHoldId = ownHolds.stream()
                .filter(hold -> hold.endAt() == null)
                .map(MedicalHoldDetailRow::holdId)
                .findFirst()
                .orElse(null);

        List<ExcludedTimingResponse> excludedTimings = timings.stream()
                .filter(timing -> timing.bib().equals(bib) && timing.excludedHoldId() != null)
                .map(ResponseMapper::toExcludedTimingResponse)
                .toList();

        List<RunnerFinishEventRow> finishEvents = repository.findFinishEvents(raceId, bib);
        List<FinishEventInHoldResponse> finishInHold = new ArrayList<>();
        for (MedicalHoldDetailRow hold : ownHolds) {
            for (RunnerFinishEventRow event : finishEvents) {
                if (event.createdAt() >= hold.startAt()
                        && (hold.endAt() == null || event.createdAt() < hold.endAt())) {
                    finishInHold.add(
                            ResponseMapper.toFinishEventInHoldResponse(hold, event));
                }
            }
        }

        Map<String, Integer> excludedCountByHold = new LinkedHashMap<>();
        for (CheckpointTimingRow timing : timings) {
            if (timing.bib().equals(bib) && timing.excludedHoldId() != null) {
                excludedCountByHold.merge(timing.excludedHoldId(), 1, Integer::sum);
            }
        }
        List<MedicalHoldHistoryItem> holdItems = ownHolds.stream()
                .map(detail -> ResponseMapper.toHistoryItem(
                        detail, excludedCountByHold.getOrDefault(detail.holdId(), 0)))
                .toList();

        return new RunnerMedicalDiagnosisResponse(
                raceId, race.version(), bib, ownEntry.status(), runner.withdrawn(),
                hasEffectiveDisqualification(raceId, bib),
                activeHoldId, excludedTimings, finishInHold, holdItems);
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
        // 先取 race 行写锁并持有到事务结束：本事务随后读到的选手/处罚/分段/暂停数据
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

    private static void rejectIfWithdrawn(RunnerRow runner) {
        if (runner.withdrawn()) {
            throw new ConflictException("选手已退赛: " + runner.bib());
        }
    }

    /** 是否存在生效（未撤销）的取消资格处罚。 */
    private boolean hasEffectiveDisqualification(String raceId, String bib) {
        return repository.findPenalties(raceId).stream()
                .anyMatch(penalty -> penalty.bib().equals(bib)
                        && !penalty.revoked()
                        && penalty.type() == PenaltyType.DISQUALIFY);
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

    private static Map<String, String> holdReasonMap(List<MedicalHoldRow> holds) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (MedicalHoldRow hold : holds) {
            reasons.put(hold.holdId(), hold.reason());
        }
        return reasons;
    }

    /**
     * 构造封榜快照的全部分段明细：每名选手 × 每个检查点一行，
     * 已有通过记录固化耗时与 timingId，缺失检查点对应字段为 null；
     * MEDICAL_HOLD 分段同时固化排除暂停ID与暂停原因；顺序为参赛号、检查点顺序。
     */
    private List<SnapshotCheckpointRow> buildSnapshotCheckpoints(
            String raceId,
            List<RunnerRow> runners,
            List<CheckpointRow> checkpoints,
            List<CheckpointTimingRow> timings,
            List<MedicalHoldDetailRow> holds) {
        Map<String, Map<String, CheckpointTimingRow>> byBibAndCode = new TreeMap<>();
        for (CheckpointTimingRow timing : timings) {
            byBibAndCode
                    .computeIfAbsent(timing.bib(), key -> new TreeMap<>())
                    .put(timing.checkpointCode(), timing);
        }
        Map<String, String> reasonByHold = new LinkedHashMap<>();
        for (MedicalHoldDetailRow hold : holds) {
            reasonByHold.put(hold.holdId(), hold.reason());
        }
        List<SnapshotCheckpointRow> rows = new ArrayList<>();
        // runners 已按参赛号字典序返回，保证无分段记录的选手也被完整固化为“全部缺失”。
        for (RunnerRow runner : runners) {
            appendSnapshotCheckpointRows(
                    rows, raceId, runner.bib(), checkpoints,
                    byBibAndCode.getOrDefault(runner.bib(), Map.of()), reasonByHold);
        }
        return rows;
    }

    private void appendSnapshotCheckpointRows(
            List<SnapshotCheckpointRow> rows,
            String raceId,
            String bib,
            List<CheckpointRow> checkpoints,
            Map<String, CheckpointTimingRow> timingByCode,
            Map<String, String> reasonByHold) {
        for (CheckpointRow checkpoint : checkpoints) {
            CheckpointTimingRow timing = timingByCode.get(checkpoint.checkpointCode());
            if (timing == null) {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), null, null));
            } else if (timing.excludedHoldId() == null) {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), timing.elapsedMillis(), timing.timingId()));
            } else {
                rows.add(new SnapshotCheckpointRow(
                        raceId, bib, checkpoint.checkpointCode(),
                        checkpoint.position(), timing.elapsedMillis(), timing.timingId(),
                        timing.excludedHoldId(),
                        reasonByHold.get(timing.excludedHoldId())));
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
