package com.example.starter.service;

import com.example.starter.domain.IdempotencyRecord;
import com.example.starter.domain.PressureSample;
import com.example.starter.domain.RunMode;
import com.example.starter.domain.RunSegment;
import com.example.starter.domain.SwitchRequest;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.PressureSampleRepository;
import com.example.starter.repo.RunSegmentRepository;
import com.example.starter.repo.SwitchRequestRepository;
import com.example.starter.repo.WellLockRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 地热井运行段、压力采样与注入/采出切换审核的核心服务。
 * 同一井的写操作在同一事务内先锁定 well_lock 行，保证作废与审核基于同一已提交有效集合，
 * 且并发切换最多生成一个后继运行段。
 */
@Service
public class WellService {

    /** 状态：待审核。 */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态：已批准。 */
    public static final String STATUS_APPROVED = "APPROVED";
    /** 状态：已拒绝。 */
    public static final String STATUS_REJECTED = "REJECTED";

    private static final String OUTCOME_APPROVED = "APPROVED";
    private static final String OUTCOME_OPERATOR_REJECTED = "OPERATOR_REJECTED";
    private static final String OUTCOME_CONDITION_FAILED = "CONDITION_FAILED";

    private final WellLockRepository wellLockRepo;
    private final RunSegmentRepository segmentRepo;
    private final PressureSampleRepository sampleRepo;
    private final SwitchRequestRepository switchRepo;
    private final IdempotencyRepository idemRepo;
    private final Clock clock;

    public WellService(WellLockRepository wellLockRepo, RunSegmentRepository segmentRepo,
            PressureSampleRepository sampleRepo, SwitchRequestRepository switchRepo,
            IdempotencyRepository idemRepo, Clock clock) {
        this.wellLockRepo = wellLockRepo;
        this.segmentRepo = segmentRepo;
        this.sampleRepo = sampleRepo;
        this.switchRepo = switchRepo;
        this.idemRepo = idemRepo;
        this.clock = clock;
    }

    /** 创建运行段；新段开始时刻不得早于旧段结束时刻，否则 409。 */
    @Transactional
    public RunSegment createSegment(String wellNo, String requestId, RunMode mode, Instant startTime) {
        lockWell(wellNo);
        String scope = "segment:" + wellNo;
        String hash = PayloadHash.of(mode.name(), startTime.toString());
        Optional<IdempotencyRecord> replay = replay(scope, requestId, hash);
        if (replay.isPresent()) {
            return segmentRepo.findById(replay.get().entityId()).orElseThrow();
        }
        Optional<RunSegment> latest = segmentRepo.findLatest(wellNo);
        if (latest.isPresent()) {
            RunSegment last = latest.get();
            if (last.getEndTime() == null) {
                throw ApiException.conflict("井 " + wellNo + " 已有进行中的运行段");
            }
            if (startTime.isBefore(last.getEndTime())) {
                throw ApiException.conflict("新运行段开始时刻早于旧段结束时刻（运行段重叠）");
            }
        }
        RunSegment segment = new RunSegment();
        segment.setWellNo(wellNo);
        segment.setMode(mode);
        segment.setStartTime(startTime);
        segment.setRequestId(requestId);
        segmentRepo.insert(segment);
        idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, segment.getId(), 201, null));
        return segment;
    }

    /** 追加压力采样；采样时刻早于运行段开始或已有最新有效采样时按时间逆序返回 422。 */
    @Transactional
    public PressureSample addSample(String wellNo, String requestId, BigDecimal pressureMpa, Instant sampledAt) {
        lockWell(wellNo);
        String scope = "sample:" + wellNo;
        String hash = PayloadHash.of(pressureMpa.stripTrailingZeros().toPlainString(), sampledAt.toString());
        Optional<IdempotencyRecord> replay = replay(scope, requestId, hash);
        if (replay.isPresent()) {
            return sampleRepo.findById(replay.get().entityId()).orElseThrow();
        }
        RunSegment open = segmentRepo.findOpen(wellNo)
                .orElseThrow(() -> ApiException.conflict("井 " + wellNo + " 当前没有进行中的运行段"));
        if (sampledAt.isBefore(open.getStartTime())) {
            throw ApiException.unprocessable("采样时刻早于运行段开始时刻（时间逆序）");
        }
        Optional<Instant> latestValid = sampleRepo.findLatestValidTime(open.getId());
        if (latestValid.isPresent() && sampledAt.isBefore(latestValid.get())) {
            throw ApiException.unprocessable("采样时刻早于已有最新有效采样（时间逆序）");
        }
        PressureSample sample = new PressureSample();
        sample.setWellNo(wellNo);
        sample.setSegmentId(open.getId());
        sample.setPressureMpa(pressureMpa);
        sample.setSampledAt(sampledAt);
        sample.setRequestId(requestId);
        sampleRepo.insert(sample);
        idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, sample.getId(), 201, null));
        return sample;
    }
    /** 作废错误读数并追加替代读数；原行只追加作废标记，已审核切换引用的快照不变。 */
    @Transactional
    public VoidResult voidSample(String wellNo, long sampleId, String requestId, String reason,
            BigDecimal replacementPressure, Instant replacementSampledAt) {
        lockWell(wellNo);
        String scope = "void:" + wellNo;
        String hash = PayloadHash.of(Long.toString(sampleId), reason,
                replacementPressure.stripTrailingZeros().toPlainString(), replacementSampledAt.toString());
        Optional<IdempotencyRecord> replay = replay(scope, requestId, hash);
        if (replay.isPresent()) {
            PressureSample voided = sampleRepo.findById(replay.get().entityId()).orElseThrow();
            return new VoidResult(voided, sampleRepo.findById(voided.getReplacedById()).orElseThrow());
        }
        PressureSample sample = sampleRepo.findById(sampleId)
                .filter(found -> found.getWellNo().equals(wellNo))
                .orElseThrow(() -> ApiException.notFound("采样 #" + sampleId + " 不存在"));
        if (sample.isVoided()) {
            throw ApiException.conflict("采样 #" + sampleId + " 已作废");
        }
        if (replacementSampledAt.isBefore(sample.getSampledAt())) {
            throw ApiException.unprocessable("替代读数时刻早于被作废读数（时间逆序）");
        }
        RunSegment segment = segmentRepo.findById(sample.getSegmentId()).orElseThrow();
        if (segment.getEndTime() == null) {
            Optional<Instant> latestValid = sampleRepo.findLatestValidTime(segment.getId());
            if (latestValid.isPresent() && replacementSampledAt.isBefore(latestValid.get())) {
                throw ApiException.unprocessable("替代读数时刻早于已有最新有效采样（时间逆序）");
            }
        }
        PressureSample replacement = new PressureSample();
        replacement.setWellNo(wellNo);
        replacement.setSegmentId(sample.getSegmentId());
        replacement.setPressureMpa(replacementPressure);
        replacement.setSampledAt(replacementSampledAt);
        replacement.setRequestId(requestId + "#replacement");
        sampleRepo.insert(replacement);
        sampleRepo.markVoided(sampleId, reason, Instant.now(clock), replacement.getId());
        idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, sampleId, 200, null));
        return new VoidResult(sampleRepo.findById(sampleId).orElseThrow(), replacement);
    }

    /** 申请切换；条件不满足时落库一条 REJECTED 记录并返回 422，旧模式保持有效。 */
    @Transactional(noRollbackFor = ApiException.class)
    public SwitchRequest createSwitchRequest(String wellNo, String requestId, RunMode targetMode) {
        lockWell(wellNo);
        String scope = "switch:" + wellNo;
        String hash = PayloadHash.of(targetMode.name());
        Optional<IdempotencyRecord> replay = replay(scope, requestId, hash);
        if (replay.isPresent()) {
            IdempotencyRecord record = replay.get();
            if (record.httpStatus() == 422) {
                throw ApiException.unprocessable(record.errorMessage(),
                        Map.of("switchRequestId", record.entityId()));
            }
            return switchRepo.findById(record.entityId()).orElseThrow();
        }
        RunSegment open = segmentRepo.findOpen(wellNo)
                .orElseThrow(() -> ApiException.conflict("井 " + wellNo + " 当前没有进行中的运行段"));
        if (open.getMode() == targetMode) {
            throw ApiException.unprocessable("目标模式与当前模式相同");
        }
        if (switchRepo.findPendingByWell(wellNo).isPresent()) {
            throw ApiException.conflict("井 " + wellNo + " 已存在待审核的切换申请");
        }
        Instant now = Instant.now(clock);
        List<PressureSample> valid = validWindowSamples(open.getId(), now);
        Optional<String> failure = SwitchConditionEvaluator.evaluate(valid);
        SwitchRequest request = new SwitchRequest();
        request.setWellNo(wellNo);
        request.setFromSegmentId(open.getId());
        request.setTargetMode(targetMode);
        request.setRequestId(requestId);
        request.setCreatedAt(now);
        if (failure.isPresent()) {
            request.setStatus(STATUS_REJECTED);
            request.setRejectReason(failure.get());
            request.setDecisionOutcome(OUTCOME_CONDITION_FAILED);
            request.setDecidedAt(now);
            switchRepo.insert(request);
            idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, request.getId(), 422, failure.get()));
            throw ApiException.unprocessable(failure.get(), Map.of("switchRequestId", request.getId()));
        }
        request.setStatus(STATUS_PENDING);
        switchRepo.insert(request);
        idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, request.getId(), 201, null));
        return request;
    }
    /** 审核决定；批准时基于当前已提交有效集合重新评估，冻结采样快照并生成后继运行段。 */
    @Transactional(noRollbackFor = ApiException.class)
    public SwitchRequest decide(long switchRequestId, String requestId, boolean approve, String reason) {
        SwitchRequest found = switchRepo.findById(switchRequestId)
                .orElseThrow(() -> ApiException.notFound("切换申请 #" + switchRequestId + " 不存在"));
        lockWell(found.getWellNo());
        SwitchRequest request = switchRepo.findById(switchRequestId).orElseThrow();
        String scope = "decision:" + switchRequestId;
        String hash = PayloadHash.of(Boolean.toString(approve), reason == null ? "" : reason);
        Optional<IdempotencyRecord> replay = replay(scope, requestId, hash);
        if (replay.isPresent()) {
            IdempotencyRecord record = replay.get();
            if (record.httpStatus() == 422) {
                throw ApiException.unprocessable(record.errorMessage(),
                        Map.of("switchRequestId", record.entityId()));
            }
            return switchRepo.findById(record.entityId()).orElseThrow();
        }
        if (!STATUS_PENDING.equals(request.getStatus())) {
            throw ApiException.conflict("切换申请 #" + switchRequestId + " 已完成审核，不能重复决定");
        }
        Instant now = Instant.now(clock);
        request.setDecidedAt(now);
        request.setDecisionRequestId(requestId);
        if (!approve) {
            request.setStatus(STATUS_REJECTED);
            request.setRejectReason(reason == null || reason.isBlank() ? "审核人拒绝" : reason);
            request.setDecisionOutcome(OUTCOME_OPERATOR_REJECTED);
            switchRepo.updateDecision(request);
            idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, request.getId(), 200, null));
            return request;
        }
        RunSegment open = segmentRepo.findOpen(request.getWellNo())
                .orElseThrow(() -> ApiException.conflict("井 " + request.getWellNo() + " 当前没有进行中的运行段"));
        if (!open.getId().equals(request.getFromSegmentId())) {
            throw ApiException.conflict("申请时的运行段已结束，无法按原申请切换");
        }
        List<PressureSample> valid = validWindowSamples(open.getId(), now);
        Optional<String> failure = SwitchConditionEvaluator.evaluate(valid);
        if (failure.isPresent()) {
            request.setStatus(STATUS_REJECTED);
            request.setRejectReason(failure.get());
            request.setDecisionOutcome(OUTCOME_CONDITION_FAILED);
            switchRepo.updateDecision(request);
            idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, request.getId(), 422, failure.get()));
            throw ApiException.unprocessable(failure.get(), Map.of("switchRequestId", request.getId()));
        }
        switchRepo.insertFrozenSamples(request.getId(), valid.stream().map(PressureSample::getId).toList());
        segmentRepo.close(open.getId(), now);
        RunSegment next = new RunSegment();
        next.setWellNo(request.getWellNo());
        next.setMode(request.getTargetMode());
        next.setStartTime(now);
        next.setRequestId("switch:" + request.getId());
        segmentRepo.insert(next);
        request.setStatus(STATUS_APPROVED);
        request.setNewSegmentId(next.getId());
        request.setNewModeStart(now);
        request.setDecisionOutcome(OUTCOME_APPROVED);
        switchRepo.updateDecision(request);
        idemRepo.insert(new IdempotencyRecord(scope, requestId, hash, request.getId(), 200, null));
        return request;
    }

    /** 查询井时间线：运行段序列、每次审核冻结的采样快照与拒绝原因。 */
    @Transactional(readOnly = true)
    public TimelineView timeline(String wellNo) {
        List<RunSegment> segments = segmentRepo.findByWell(wellNo);
        List<TimelineView.SwitchRequestView> requests = switchRepo.findByWell(wellNo).stream()
                .map(item -> new TimelineView.SwitchRequestView(item, switchRepo.findFrozenSamples(item.getId())))
                .toList();
        return new TimelineView(wellNo, segments, requests);
    }

    private void lockWell(String wellNo) {
        wellLockRepo.ensureRow(wellNo);
        wellLockRepo.lock(wellNo);
    }

    private Optional<IdempotencyRecord> replay(String scope, String requestId, String hash) {
        Optional<IdempotencyRecord> existing = idemRepo.find(scope, requestId);
        if (existing.isPresent() && !existing.get().payloadHash().equals(hash)) {
            throw ApiException.conflict("requestId " + requestId + " 已被使用且参数不一致");
        }
        return existing;
    }

    private List<PressureSample> validWindowSamples(long segmentId, Instant now) {
        return sampleRepo.findValidInWindow(segmentId, now.minus(SwitchConditionEvaluator.WINDOW), now);
    }
}