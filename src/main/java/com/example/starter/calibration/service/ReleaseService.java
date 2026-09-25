package com.example.starter.calibration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.ReleaseBindingConflictException;
import com.example.starter.calibration.api.dto.ReleaseDiagnosticResponse;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CertificateBatchBinding;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.MeasurementVersion;
import com.example.starter.calibration.repo.CertificateBatchBindingRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.MeasurementVersionRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效。
 *
 * <p>门禁：处于待放行、判定合格、放行人不同于提交人；测量当前版本引用的标准器证书未撤销、
 * 在测量时刻有效（提交时已保证，撤销是唯一运行时失效来源）；补偿系数、不确定度版本在版本
 * 血缘中完整可追溯；singleBatchOnly 证书只能被一个放行批次引用，首次放行引用即绑定该批次，
 * 其他批次再引用整批拒绝（422）并返回已绑定批次。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final MeasurementVersionRepository versions;
    private final CertificateRepository certificates;
    private final CertificateBatchBindingRepository bindings;
    private final ReleaseRepository releases;
    private final Clock clock;

    public ReleaseService(MeasurementRepository measurements,
                          MeasurementVersionRepository versions,
                          CertificateRepository certificates,
                          CertificateBatchBindingRepository bindings,
                          ReleaseRepository releases,
                          Clock clock) {
        this.measurements = measurements;
        this.versions = versions;
        this.certificates = certificates;
        this.bindings = bindings;
        this.releases = releases;
        this.clock = clock;
    }

    /**
     * 原子批量放行。先按测量键字典序、再按证书 ID 升序加锁，避免并发批次间死锁；
     * 证书行锁使撤销、绑定与放行按事务提交顺序裁决。任一门禁不满足整批回滚、状态不变。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        List<String> orderedKeys = validateKeys(keys);

        Evaluation evaluation = evaluate(orderedKeys, releaser, false);
        if (!evaluation.failures.isEmpty()) {
            if (evaluation.hasBindingConflict) {
                throw new ReleaseBindingConflictException(evaluation.failures);
            }
            throw new BatchRejectedException(evaluation.failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant now = clock.instant();
        // 全部通过后才写绑定与放行记录；同一批内多个测量引用同一 singleBatchOnly 证书只绑定一次
        Set<Long> boundInThisBatch = new HashSet<>();
        for (Measurement measurement : evaluation.approved) {
            Certificate cert = evaluation.certs.get(measurement.certificateId());
            if (cert.singleBatchOnly() && boundInThisBatch.add(cert.id())) {
                bindings.bindIfAbsent(cert.id(), batchId, now);
            }
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), measurement.versionNo(), releaser, now);
        }
        return new ReleaseResponse(batchId, releaser, now, orderedKeys);
    }

    /**
     * 放行诊断：按与正式放行相同的门禁逐项给出原因，但不绑定、不放行、不改变任何状态。
     * actor 为空时跳过放行人校验。
     */
    @Transactional(readOnly = true)
    public ReleaseDiagnosticResponse diagnose(List<String> keys, String actor) {
        List<String> orderedKeys = validateKeys(keys);
        String releaser = Inputs.optionalText(actor);
        Evaluation evaluation = evaluate(orderedKeys, releaser, true);
        return new ReleaseDiagnosticResponse(evaluation.failures.isEmpty(), evaluation.failures);
    }

    /**
     * 校验批量键的基本约束并返回去空白后的列表（保持请求顺序）。
     */
    private List<String> validateKeys(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }
        return orderedKeys;
    }

    /**
     * 在持有测量行锁与证书行锁后逐项评估门禁。
     *
     * @param dryOnly true 表示诊断模式：不写入绑定，且不预知当前批次 ID
     */
    private Evaluation evaluate(List<String> orderedKeys, String releaser, boolean dryOnly) {
        // 门禁裁决时刻：证书若在此时已到期（now >= validTo，端点到期即无效）则整批拒绝
        Instant gateTime = clock.instant();
        // 第一阶段：按测量键字典序加测量行锁
        Map<String, Measurement> lockedMeasurements = new HashMap<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            measurements.findByKeyForUpdate(key).ifPresent(m -> lockedMeasurements.put(key, m));
        }
        // 第二阶段：涉及的证书按 ID 升序加行锁，统一锁序避免跨批次死锁
        List<Long> certIds = lockedMeasurements.values().stream()
                .map(Measurement::certificateId).distinct().sorted().toList();
        Map<Long, Certificate> lockedCerts = new HashMap<>();
        for (Long certId : certIds) {
            Certificate cert = certificates.findByIdForUpdate(certId)
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + certId));
            lockedCerts.put(certId, cert);
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        boolean hasBindingConflict = false;

        for (String key : orderedKeys) {
            Measurement measurement = lockedMeasurements.get(key);
            if (measurement == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            String boundBatchId = null;

            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                reasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
                reasons.add("NOT_PASSED");
            }

            Certificate cert = lockedCerts.get(measurement.certificateId());
            if (cert == null) {
                reasons.add("CERTIFICATE_MISSING");
            } else {
                if (cert.revoked()) {
                    reasons.add("CERTIFICATE_REVOKED");
                } else {
                    // 到期以“放行门禁时刻”判断（now >= validTo，端点到期即无效）；
                    // 另防御性覆盖测量时刻未生效等历史脏数据。
                    if (!gateTime.isBefore(cert.validTo())
                            || !measurement.measuredAt().isBefore(cert.validTo())) {
                        reasons.add("CERTIFICATE_EXPIRED");
                    } else if (measurement.measuredAt().isBefore(cert.validFrom())) {
                        reasons.add("CERTIFICATE_NOT_YET_VALID");
                    }
                }
                if (!traceable(measurement, cert)) {
                    reasons.add("TRACEABILITY_INCOMPLETE");
                }
                if (cert.singleBatchOnly()) {
                    // 评估阶段尚未写入本批次绑定，故能查到的绑定必属其他已提交批次（证书行锁串行化）
                    var binding = dryOnly
                            ? bindings.findByCertificateId(cert.id())
                            : bindings.findByCertificateIdForUpdate(cert.id());
                    if (binding.isPresent()) {
                        boundBatchId = binding.get().batchId();
                        reasons.add("CERTIFICATE_BOUND_TO_OTHER_BATCH");
                        if (!dryOnly) {
                            hasBindingConflict = true;
                        }
                    }
                }
            }

            if (releaser != null && measurement.submittedBy().equals(releaser)) {
                reasons.add("SAME_ACTOR");
            }

            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons, boundBatchId));
            }
        }

        return new Evaluation(failures, approved, hasBindingConflict, lockedCerts);
    }

    /**
     * 可追溯性：当前测量版本必须存在版本血缘快照，且快照中的证书 ID/版本、不确定度版本
     * 与当前引用证书一致，补偿系数快照与当前证书一致。
     */
    private boolean traceable(Measurement measurement, Certificate cert) {
        if (cert.version() == null || cert.version().isBlank()
                || cert.uncertaintyVersion() == null || cert.uncertaintyVersion().isBlank()) {
            return false;
        }
        MeasurementVersion snapshot = versions
                .findByMeasurementAndNo(measurement.id(), measurement.versionNo()).orElse(null);
        if (snapshot == null) {
            return false;
        }
        return snapshot.certificateId() == cert.id()
                && cert.version().equals(snapshot.certificateVersion())
                && cert.uncertaintyVersion().equals(snapshot.uncertaintyVersion())
                && cert.a().compareTo(snapshot.a()) == 0
                && cert.b().compareTo(snapshot.b()) == 0;
    }

    /**
     * 评估结果聚合。
     */
    private static final class Evaluation {
        private final List<ItemFailure> failures;
        private final List<Measurement> approved;
        private final boolean hasBindingConflict;
        private final Map<Long, Certificate> certs;

        private Evaluation(List<ItemFailure> failures, List<Measurement> approved,
                           boolean hasBindingConflict, Map<Long, Certificate> certs) {
            this.failures = failures;
            this.approved = approved;
            this.hasBindingConflict = hasBindingConflict;
            this.certs = certs;
        }
    }
}
