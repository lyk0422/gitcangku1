package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchGateException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.RejectRequest;
import com.example.starter.calibration.api.dto.RejectResponse;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticItem;
import com.example.starter.calibration.api.dto.ReleaseRequest;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行、驳回与放行诊断服务。
 * 放行使用补偿后测量值与不确定度：启用批次不确定度上限时，任一测量缺环境、补偿后超规格或
 * 不确定度超限则整批 422 并稳定列出测量标识；结构性状态冲突维持整批 409；既有放行状态不变。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final IdempotentExecutor idempotency;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          IdempotentExecutor idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.idempotency = idempotency;
    }

    /** 校验后的批次上下文。releaser 为空表示仅做环境门禁诊断（重算内部评估），跳过放行人相关判定。 */
    record BatchContext(List<String> sortedKeys, String releaser, BigDecimal uncertaintyLimit) {
    }

    /** 单项评估结果。 */
    static final class Evaluation {
        String key;
        Measurement measurement;
        boolean certificateRevoked;
        final List<String> gateReasons = new ArrayList<>();
        final List<String> structuralReasons = new ArrayList<>();
    }

    /**
     * 原子批量放行。行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效。
     */
    public ReleaseResponse release(ReleaseRequest request, String actor) {
        BatchContext ctx = validateRequest(request == null ? null : request.keys(),
                request == null ? null : request.uncertaintyLimit(), actor);
        List<String> requestedOrder = request.keys().stream().map(String::trim).toList();
        IdempotentExecutor.Result<ReleaseResponse> result = idempotency.execute(
                request.calcKey(), "RELEASE",
                () -> doRelease(ctx),
                resp -> releaseReplayFingerprint(ctx, resp),
                ReleaseResponse.class);
        // 重放保持请求键顺序返回
        return new ReleaseResponse(result.value().batchId(), result.value().releasedBy(),
                result.value().releasedAt(), requestedOrder);
    }

    private IdempotentExecutor.Outcome<ReleaseResponse> doRelease(BatchContext ctx) {
        List<Evaluation> evaluations = evaluateLocked(ctx);

        List<ItemFailure> gateFailures = new ArrayList<>();
        List<ItemFailure> structuralFailures = new ArrayList<>();
        for (Evaluation e : evaluations) {
            if (!e.gateReasons.isEmpty()) {
                gateFailures.add(new ItemFailure(e.key, List.copyOf(e.gateReasons)));
            }
            if (!e.structuralReasons.isEmpty()) {
                structuralFailures.add(new ItemFailure(e.key, List.copyOf(e.structuralReasons)));
            }
        }
        // 环境补偿门禁优先：任一缺环境/补偿后超规格/不确定度超限 → 整批 422，既有状态不变。
        if (!gateFailures.isEmpty()) {
            throw new BatchGateException(gateFailures);
        }
        if (!structuralFailures.isEmpty()) {
            throw new BatchRejectedException(structuralFailures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Evaluation e : evaluations) {
            measurements.markReleased(e.measurement.id());
            releases.insert(batchId, e.measurement.id(), ctx.releaser(), releasedAt);
        }
        ReleaseResponse response = new ReleaseResponse(
                batchId, ctx.releaser(), releasedAt, List.copyOf(ctx.sortedKeys()));
        return IdempotentExecutor.Outcome.of(response, releaseFingerprint(ctx, evaluations));
    }

    private String releaseReplayFingerprint(BatchContext ctx, ReleaseResponse resp) {
        // 重放时按固化响应中的批次键重新加锁读取版本行重建指纹；
        // 已放行测量的最新版本即放行版本行（已放行不可重算），版本快照不变，指纹可复现。
        List<Evaluation> evaluations = evaluateLocked(new BatchContext(
                resp.released().stream().sorted().toList(), ctx.releaser(), ctx.uncertaintyLimit()));
        return releaseFingerprint(ctx, evaluations);
    }

    private String releaseFingerprint(BatchContext ctx, List<Evaluation> evaluations) {
        StringBuilder sb = new StringBuilder("actor=").append(ctx.releaser())
                .append("|limit=").append(ctx.uncertaintyLimit() == null
                        ? "" : ctx.uncertaintyLimit().stripTrailingZeros().toPlainString());
        for (Evaluation e : evaluations) {
            sb.append("|item=").append(e.key);
            if (e.measurement == null) {
                sb.append(",missing");
                continue;
            }
            Measurement m = e.measurement;
            String uncText = m.uncertainty() == null ? "" : DtoMapper.format(m.uncertainty());
            // 仅含不可变版本快照，保证放行前评估与放行后重放指纹一致（状态在放行后会改变）。
            sb.append(",vid=").append(m.id())
                    .append(",coeff=").append(m.coefficientId() == null ? "" : m.coefficientId())
                    .append(",env=").append(m.hasEnvironment())
                    .append(",value=").append(DtoMapper.format(m.releaseValue()))
                    .append(",unc=").append(uncText);
        }
        return sb.toString();
    }

    /**
     * 批量驳回（原子）：仅待放行版本可驳回；已放行不可驳回（快照不改写）；重复驳回 409。
     */
    public RejectResponse reject(RejectRequest request, String actor) {
        BatchContext ctx = validateRequest(request == null ? null : request.keys(),
                null, actor);
        String reason = Inputs.requireText(request == null ? null : request.reason(), "reason");
        IdempotentExecutor.Result<RejectResponse> result = idempotency.execute(
                request == null ? null : request.calcKey(), "REJECT",
                () -> doReject(ctx, reason),
                resp -> rejectReplayFingerprint(ctx, reason, resp),
                RejectResponse.class);
        return result.value();
    }

    private IdempotentExecutor.Outcome<RejectResponse> doReject(BatchContext ctx, String reason) {
        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> targets = new ArrayList<>();
        for (String key : ctx.sortedKeys()) {
            Measurement m = measurements.findLatestByKeyForUpdate(key).orElse(null);
            if (m == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            if (m.status() == MeasurementStatus.RELEASED) {
                failures.add(new ItemFailure(key, List.of("ALREADY_RELEASED")));
            } else if (m.status() == MeasurementStatus.REJECTED) {
                failures.add(new ItemFailure(key, List.of("ALREADY_REJECTED")));
            } else {
                targets.add(m);
            }
        }
        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }
        String batchId = UUID.randomUUID().toString();
        Instant rejectedAt = Instant.now();
        for (Measurement m : targets) {
            measurements.markRejected(m.id(), ctx.releaser(), rejectedAt, reason);
        }
        RejectResponse response = new RejectResponse(
                batchId, ctx.releaser(), rejectedAt, reason, List.copyOf(ctx.sortedKeys()));
        // 指纹仅含版本行 ID（不可变快照）与输入，不含驳回后变化的状态。
        StringBuilder sb = new StringBuilder("actor=").append(ctx.releaser())
                .append("|reason=").append(reason);
        for (Measurement m : targets) {
            sb.append("|item=").append(m.measurementKey()).append(",vid=").append(m.id());
        }
        return IdempotentExecutor.Outcome.of(response, sb.toString());
    }

    private String rejectReplayFingerprint(BatchContext ctx, String reason, RejectResponse resp) {
        StringBuilder sb = new StringBuilder("actor=").append(ctx.releaser())
                .append("|reason=").append(reason);
        for (String key : resp.rejected()) {
            Measurement m = measurements.findLatestByKey(key).orElse(null);
            sb.append("|item=").append(key).append(",vid=")
                    .append(m == null ? "" : m.id());
        }
        return sb.toString();
    }

    /**
     * 放行诊断：逐测量稳定（字典序）给出放行判定原因，不改变任何状态。
     * uncertaintyLimit 提供时启用环境补偿门禁诊断。
     */
    @Transactional(readOnly = true)
    public List<ReleaseDiagnosticItem> diagnose(List<String> keys, String uncertaintyLimit, String actor) {
        BatchContext ctx = validateRequest(keys, uncertaintyLimit, actor);
        List<Evaluation> evaluations = evaluateReadOnly(ctx);
        List<ReleaseDiagnosticItem> items = new ArrayList<>();
        for (Evaluation e : evaluations) {
            items.add(toDiagnostic(e, ctx));
        }
        return items;
    }

    private ReleaseDiagnosticItem toDiagnostic(Evaluation e, BatchContext ctx) {
        Measurement m = e.measurement;
        List<String> reasons = new ArrayList<>();
        reasons.addAll(e.gateReasons);
        reasons.addAll(e.structuralReasons);
        reasons.sort(java.util.Comparator.naturalOrder());
        if (m == null) {
            return new ReleaseDiagnosticItem(e.key, null, "NOT_FOUND", null, null, null,
                    null, null, false, false, reasons);
        }
        boolean passable = reasons.isEmpty();
        return new ReleaseDiagnosticItem(
                e.key,
                m.versionNo(),
                m.status().name(),
                DtoMapper.format(m.releaseValue()),
                DtoMapper.format(m.lowerLimit()),
                DtoMapper.format(m.upperLimit()),
                DtoMapper.format(m.uncertainty()),
                ctx.uncertaintyLimit() == null ? null : DtoMapper.format(ctx.uncertaintyLimit()),
                m.hasEnvironment(),
                passable,
                reasons);
    }

    private BatchContext validateRequest(List<String> keys, String uncertaintyLimitRaw, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量包含重复测量键");
        }
        BigDecimal limit = null;
        if (uncertaintyLimitRaw != null && !uncertaintyLimitRaw.isBlank()) {
            limit = Inputs.requireDecimal(uncertaintyLimitRaw, "uncertaintyLimit");
            Inputs.requireNonNegative(limit, "uncertaintyLimit");
        }
        return new BatchContext(orderedKeys.stream().sorted().toList(), releaser, limit);
    }

    /**
     * 供重算事务复用：校验批次键集合（1～50、不可重复），返回字典序稳定键列表。
     */
    public List<String> validateBatchKeys(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "batchKeys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量包含重复测量键");
        }
        return orderedKeys.stream().sorted().toList();
    }

    /**
     * 供重算事务复用：在持锁状态下逐键评估环境补偿门禁（缺环境/补偿后超规格/不确定度超限），
     * 稳定（字典序）返回诊断；不改变任何状态，不做放行人/状态等结构性判定。
     */
    public List<ReleaseDiagnosticItem> evaluateGateForRecalc(List<String> sortedKeys, BigDecimal uncertaintyLimit) {
        List<ReleaseDiagnosticItem> items = new ArrayList<>();
        for (String key : sortedKeys) {
            Measurement m = measurements.findLatestByKeyForUpdate(key).orElse(null);
            if (m == null) {
                items.add(new ReleaseDiagnosticItem(key, null, "NOT_FOUND", null, null, null,
                        null, uncertaintyLimit == null ? null : DtoMapper.format(uncertaintyLimit),
                        false, false, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (uncertaintyLimit != null) {
                if (!m.hasEnvironment()) {
                    reasons.add("MISSING_ENVIRONMENT");
                } else if (Boolean.FALSE.equals(m.passedAfterComp())) {
                    reasons.add("OUT_OF_SPEC_AFTER_COMP");
                }
                if (m.uncertainty() == null
                        || m.uncertainty().compareTo(uncertaintyLimit) > 0) {
                    reasons.add("UNCERTAINTY_EXCEEDED");
                }
            }
            items.add(new ReleaseDiagnosticItem(
                    key, m.versionNo(), m.status().name(),
                    DtoMapper.format(m.releaseValue()),
                    DtoMapper.format(m.lowerLimit()), DtoMapper.format(m.upperLimit()),
                    DtoMapper.format(m.uncertainty()),
                    uncertaintyLimit == null ? null : DtoMapper.format(uncertaintyLimit),
                    m.hasEnvironment(), reasons.isEmpty(), reasons));
        }
        return items;
    }

    /**
     * 字典序逐键加最新版本行锁与证书行锁，汇总门禁原因（422）与结构性原因（409）。
     */
    private List<Evaluation> evaluateLocked(BatchContext ctx) {
        List<Evaluation> result = new ArrayList<>();
        for (String key : ctx.sortedKeys()) {
            Evaluation e = evaluateOne(key, ctx, true);
            result.add(e);
        }
        return result;
    }

    private List<Evaluation> evaluateReadOnly(BatchContext ctx) {
        List<Evaluation> result = new ArrayList<>();
        for (String key : ctx.sortedKeys()) {
            result.add(evaluateOne(key, ctx, false));
        }
        return result;
    }

    private Evaluation evaluateOne(String key, BatchContext ctx, boolean forUpdate) {
        Evaluation e = new Evaluation();
        e.key = key;
        var locked = forUpdate
                ? measurements.findLatestByKeyForUpdate(key)
                : measurements.findLatestByKey(key);
        if (locked.isEmpty()) {
            e.structuralReasons.add("MEASUREMENT_NOT_FOUND");
            return e;
        }
        Measurement m = locked.get();
        e.measurement = m;

        // 环境补偿门禁（仅在批次提供不确定度上限时启用）。
        if (ctx.uncertaintyLimit() != null) {
            if (!m.hasEnvironment()) {
                e.gateReasons.add("MISSING_ENVIRONMENT");
            } else if (Boolean.FALSE.equals(m.passedAfterComp())) {
                e.gateReasons.add("OUT_OF_SPEC_AFTER_COMP");
            }
            if (m.uncertainty() == null
                    || m.uncertainty().compareTo(ctx.uncertaintyLimit()) > 0) {
                e.gateReasons.add("UNCERTAINTY_EXCEEDED");
            }
        }

        // 结构性放行条件。
        if (m.status() == MeasurementStatus.RELEASED) {
            e.structuralReasons.add("ALREADY_RELEASED");
        } else if (m.status() != MeasurementStatus.PENDING) {
            e.structuralReasons.add("NOT_PENDING");
        }
        boolean specPassed = m.compensated()
                ? Boolean.TRUE.equals(m.passedAfterComp())
                : m.passed();
        if (!specPassed) {
            e.structuralReasons.add("NOT_PASSED");
        }
        Certificate cert = certificates.findById(m.certificateId()).orElse(null);
        e.certificateRevoked = cert == null || cert.revoked();
        if (forUpdate) {
            cert = certificates.findByIdForUpdate(m.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + m.certificateId()));
            e.certificateRevoked = cert.revoked();
        }
        if (e.certificateRevoked) {
            e.structuralReasons.add("CERTIFICATE_REVOKED");
        }
        if (m.submittedBy().equals(ctx.releaser())) {
            e.structuralReasons.add("SAME_ACTOR");
        }
        return e;
    }
}
