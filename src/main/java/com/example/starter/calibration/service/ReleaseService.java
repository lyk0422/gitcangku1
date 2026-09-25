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
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.GateRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticsResponse;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效。
 *
 * <ul>
 *   <li>不带不确定度上限：沿用既有规则（状态/合格/证书/放行人），任一不符整批 409。</li>
 *   <li>带不确定度上限：补偿门禁——任一测量缺少环境、补偿后超规格或不确定度超过批次上限，
 *       整次 422 并稳定（字典序）列出测量标识与原因；既有放行状态不变。</li>
 * </ul>
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

    /**
     * 原子批量放行。行锁按测量键字典序获取，避免并发批次间死锁；
     * 证书行锁使撤销与放行按事务提交顺序生效。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor, String uncertaintyLimitText) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }
        BigDecimal uncertaintyLimit = Inputs.optionalDecimal(uncertaintyLimitText, "uncertaintyLimit");
        boolean gateMode = uncertaintyLimit != null;

        List<String> sortedKeys = orderedKeys.stream().sorted().toList();

        // calcKey 含批次键、门禁上限与各测量当前版本，任一变化产生新键；同键成功重放首次结果。
        List<Measurement> locked = new ArrayList<>();
        for (String key : sortedKeys) {
            Measurement m = measurements.findByKeyForUpdate(key).orElse(null);
            locked.add(m);
        }
        String[] versionParts = new String[sortedKeys.size()];
        for (int i = 0; i < sortedKeys.size(); i++) {
            Measurement m = locked.get(i);
            versionParts[i] = sortedKeys.get(i) + "#" + (m == null ? "X" : m.currentVersion())
                    + ":" + (m == null ? "X" : m.status());
        }
        String fingerprint = Fingerprints.of("RELEASE", releaser, gateMode ? uncertaintyLimit : null,
                (Object) versionParts);

        // 放行由测量行锁 + 状态机按事务提交顺序裁决（并发同批仅一批成功，其余 409）；
        // 门禁失败抛异常回滚不占 calcKey，成功才记录含批次版本/环境/系数版本的指纹。
        ReleaseResponse response = doRelease(sortedKeys, locked, releaser, gateMode, uncertaintyLimit);
        idempotency.recordSuccess(fingerprint, "RELEASE", 200, fingerprint, response);
        return response;
    }

    private ReleaseResponse doRelease(List<String> sortedKeys, List<Measurement> locked, String releaser,
                                      boolean gateMode, BigDecimal uncertaintyLimit) {
        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (int i = 0; i < sortedKeys.size(); i++) {
            String key = sortedKeys.get(i);
            Measurement measurement = locked.get(i);
            if (measurement == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = evaluate(measurement, releaser, gateMode, uncertaintyLimit);
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            if (gateMode) {
                throw new GateRejectedException(failures);
            }
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Measurement measurement : approved) {
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, sortedKeys);
    }

    /**
     * 计算单项放行原因（空列表表示可放行）。
     * 非门禁模式保持原有顺序（状态 → NOT_PASSED → 证书 → 放行人）；
     * 门禁模式在状态后追加缺环境/补偿超规格/不确定度超限，再检查证书与放行人。
     */
    private List<String> evaluate(Measurement measurement, String releaser, boolean gateMode,
                                  BigDecimal uncertaintyLimit) {
        List<String> reasons = new ArrayList<>();
        if (measurement.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (measurement.status() != MeasurementStatus.PENDING) {
            reasons.add("NOT_PENDING");
        }
        if (gateMode) {
            // 补偿门禁：使用补偿后测量值与不确定度。
            if (!measurement.hasEnvironment()) {
                reasons.add("MISSING_ENVIRONMENT");
            } else {
                if (measurement.compensatedPassed() == null || !measurement.compensatedPassed()) {
                    reasons.add("COMPENSATED_OUT_OF_SPEC");
                }
                if (measurement.uncertainty() == null
                        || measurement.uncertainty().compareTo(uncertaintyLimit) > 0) {
                    reasons.add("UNCERTAINTY_EXCEEDED");
                }
            }
        } else if (!measurement.passed()) {
            reasons.add("NOT_PASSED");
        }
        Certificate cert = certificates.findByIdForUpdate(measurement.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + measurement.certificateId()));
        if (cert.revoked()) {
            reasons.add("CERTIFICATE_REVOKED");
        }
        if (measurement.submittedBy().equals(releaser)) {
            reasons.add("SAME_ACTOR");
        }
        return reasons;
    }

    /**
     * 放行诊断：对一批测量按补偿门禁预评估，不改变任何状态。用于查询放行前的整批门禁结果。
     */
    @Transactional(readOnly = true)
    public ReleaseDiagnosticsResponse diagnose(List<String> keys, String uncertaintyLimitText) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("诊断条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("诊断包含重复测量键");
        }
        BigDecimal limit = Inputs.optionalDecimal(uncertaintyLimitText, "uncertaintyLimit");

        List<ReleaseDiagnosticsResponse.ItemDiagnostic> items = new ArrayList<>();
        boolean allPass = true;
        for (String key : orderedKeys.stream().sorted().toList()) {
            Measurement m = measurements.findByKey(key).orElse(null);
            if (m == null) {
                allPass = false;
                items.add(new ReleaseDiagnosticsResponse.ItemDiagnostic(
                        key, false, null, false, null, null, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (m.status() != MeasurementStatus.PENDING) {
                reasons.add(m.status() == MeasurementStatus.RELEASED ? "ALREADY_RELEASED" : "NOT_PENDING");
            }
            boolean certOk = certificates.findById(m.certificateId())
                    .map(c -> !c.revoked()).orElse(false);
            if (!certOk) {
                reasons.add("CERTIFICATE_REVOKED");
            }
            if (limit != null) {
                if (!m.hasEnvironment()) {
                    reasons.add("MISSING_ENVIRONMENT");
                } else {
                    if (m.compensatedPassed() == null || !m.compensatedPassed()) {
                        reasons.add("COMPENSATED_OUT_OF_SPEC");
                    }
                    if (m.uncertainty() == null || m.uncertainty().compareTo(limit) > 0) {
                        reasons.add("UNCERTAINTY_EXCEEDED");
                    }
                }
            } else if (!m.passed()) {
                reasons.add("NOT_PASSED");
            }
            if (!reasons.isEmpty()) {
                allPass = false;
            }
            items.add(new ReleaseDiagnosticsResponse.ItemDiagnostic(
                    key, true, m.status().name(), m.hasEnvironment(),
                    m.compensatedPassed(), m.uncertainty(), reasons));
        }
        return new ReleaseDiagnosticsResponse(orderedKeys,
                limit == null ? null : DtoMapper.format(limit), allPass, items);
    }
}
