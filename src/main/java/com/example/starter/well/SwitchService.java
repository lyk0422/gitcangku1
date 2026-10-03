package com.example.starter.well;

import com.example.starter.common.ApiException;
import com.example.starter.common.Payloads;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 切换申请与审核：申请校验最近 15 分钟窗口条件，审核基于已提交有效集合复核，
 * 批准时冻结采样快照与新段起点，拒绝时记录原因且旧模式保持有效。
 */
@Service
public class SwitchService {

    /** 窗口长度：最近 15 分钟。 */
    static final Duration WINDOW = Duration.ofMinutes(15);
    /** 相邻采样最大间隔：5 分钟。 */
    static final Duration MAX_GAP = Duration.ofMinutes(5);
    /** 合格压力下限（含），单位 MPa。 */
    static final BigDecimal MIN_PRESSURE = new BigDecimal("8.00");
    /** 合格压力上限（含），单位 MPa。 */
    static final BigDecimal MAX_PRESSURE = new BigDecimal("12.00");
    /** 窗口内最少有效采样数。 */
    static final int MIN_SAMPLES = 4;

    private final SegmentRepository segmentRepository;
    private final SampleRepository sampleRepository;
    private final SwitchRequestRepository switchRepository;
    private final Clock clock;

    public SwitchService(SegmentRepository segmentRepository, SampleRepository sampleRepository,
                         SwitchRequestRepository switchRepository, Clock clock) {
        this.segmentRepository = segmentRepository;
        this.sampleRepository = sampleRepository;
        this.switchRepository = switchRepository;
        this.clock = clock;
    }

    /**
     * 窗口条件判定结果：合格时携带所用采样，不合格时携带中文原因。
     */
    public record Eligibility(boolean ok, String reason, List<PressureSample> samples) {
    }

    /**
     * 评估最近 15 分钟窗口：至少 4 个未作废采样、相邻间隔不超过 5 分钟、
     * 每个读数处于 8 至 12 MPa（含边界）。
     */
    public Eligibility evaluate(String wellNo, Instant now) {
        List<PressureSample> valid = sampleRepository.findValidInWindow(
                wellNo, now.minus(WINDOW), now);
        if (valid.size() < MIN_SAMPLES) {
            return new Eligibility(false, "采样不足：最近 15 分钟内有效采样 " + valid.size()
                    + " 个，至少需要 " + MIN_SAMPLES + " 个", valid);
        }
        for (int i = 1; i < valid.size(); i++) {
            Duration gap = Duration.between(valid.get(i - 1).sampledAt(), valid.get(i).sampledAt());
            if (gap.compareTo(MAX_GAP) > 0) {
                return new Eligibility(false, "采样间隔超过 5 分钟：采样 " + valid.get(i - 1).id()
                        + " 与 " + valid.get(i).id() + " 间隔 " + gap.toMinutes() + " 分钟", valid);
            }
        }
        for (PressureSample sample : valid) {
            if (sample.pressureMpa().compareTo(MIN_PRESSURE) < 0
                    || sample.pressureMpa().compareTo(MAX_PRESSURE) > 0) {
                return new Eligibility(false, "读数超出 8 至 12 MPa 范围：采样 " + sample.id()
                        + " 为 " + sample.pressureMpa() + " MPa", valid);
            }
        }
        return new Eligibility(true, null, valid);
    }

    /**
     * 提交切换申请。requestId 同参重放返回原申请，异参返回 409；
     * 窗口条件不满足返回 422，旧模式保持有效。
     */
    @Transactional
    public SwitchRequest apply(String wellNo, String requestId, String toMode) {
        String hash = Payloads.hash(wellNo, "|", requestId, "|", toMode);
        Optional<SwitchRequest> existing = switchRepository.findByRequestId(wellNo, requestId);
        if (existing.isPresent()) {
            if (existing.get().payloadHash().equals(hash)) {
                return existing.get();
            }
            throw ApiException.conflict("requestId " + requestId + " 已存在但参数不一致");
        }
        WellSegment open = segmentRepository.findOpen(wellNo)
                .orElseThrow(() -> ApiException.unprocessable("井 " + wellNo + " 没有在运行的运行段"));
        if (open.mode().equals(toMode)) {
            throw ApiException.unprocessable("目标模式与当前模式相同，无需切换");
        }
        Eligibility eligibility = evaluate(wellNo, clock.instant());
        if (!eligibility.ok()) {
            throw ApiException.unprocessable(eligibility.reason());
        }
        try {
            long id = switchRepository.insert(wellNo, requestId, hash, open.mode(), toMode,
                    clock.instant());
            return switchRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            SwitchRequest replayed = switchRepository.findByRequestId(wellNo, requestId)
                    .orElseThrow(() -> e);
            if (replayed.payloadHash().equals(hash)) {
                return replayed;
            }
            throw ApiException.conflict("requestId " + requestId + " 已存在但参数不一致");
        }
    }

    /**
     * 审核切换申请。批准时基于当前已提交有效采样集合复核窗口条件，
     * 冻结所用采样快照与新段起点，结束旧段并在旧段结束时刻之后开启新段；
     * 条件不再满足（例如关键读数已作废）时拒绝并记录原因，旧模式保持有效。
     * 已决定的申请再次审核返回 409。
     */
    @Transactional
    public SwitchRequest review(long id, boolean approve, String reason, Instant newSegmentStart) {
        SwitchRequest request = switchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("切换申请 " + id + " 不存在"));
        if (!SwitchRequest.STATUS_PENDING.equals(request.status())) {
            throw ApiException.conflict("切换申请 " + id + " 已审核，状态为 " + request.status());
        }
        Instant now = clock.instant();
        if (!approve) {
            String rejectReason = reason == null || reason.isBlank() ? "审核拒绝" : reason;
            switchRepository.decide(id, SwitchRequest.STATUS_REJECTED, rejectReason, null, now);
            return switchRepository.findById(id).orElseThrow();
        }
        WellSegment open = segmentRepository.findOpenForUpdate(request.wellNo())
                .orElseThrow(() -> ApiException.conflict("井 " + request.wellNo()
                        + " 没有在运行的运行段，无法批准切换"));
        if (!open.mode().equals(request.fromMode()) || open.mode().equals(request.toMode())) {
            throw ApiException.conflict("井 " + request.wellNo() + " 当前模式已变化，申请不再适用");
        }
        Eligibility eligibility = evaluate(request.wellNo(), now);
        if (!eligibility.ok()) {
            switchRepository.decide(id, SwitchRequest.STATUS_REJECTED, eligibility.reason(), null, now);
            return switchRepository.findById(id).orElseThrow();
        }
        if (newSegmentStart == null) {
            throw ApiException.unprocessable("批准时必须提供新运行段起点 newSegmentStart");
        }
        if (!newSegmentStart.isAfter(now)) {
            throw ApiException.unprocessable("新运行段起点 " + newSegmentStart
                    + " 必须晚于旧段结束时刻 " + now);
        }
        segmentRepository.close(open.id(), now);
        segmentRepository.insert(request.wellNo(), request.toMode(), newSegmentStart, now);
        switchRepository.insertSnapshot(id, eligibility.samples());
        switchRepository.decide(id, SwitchRequest.STATUS_APPROVED, null, newSegmentStart, now);
        return switchRepository.findById(id).orElseThrow();
    }

    /** 查询井的全部切换申请，按主键升序。 */
    public List<SwitchRequest> requestsOf(String wellNo) {
        return switchRepository.findByWell(wellNo);
    }

    /** 查询审核冻结的采样快照。 */
    public List<SampleSnapshot> snapshotsOf(long switchRequestId) {
        return switchRepository.findSnapshots(switchRequestId);
    }
}