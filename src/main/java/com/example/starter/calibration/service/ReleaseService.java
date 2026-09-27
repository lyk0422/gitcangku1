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
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.UncertaintyComponentView;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;
import com.example.starter.calibration.repo.UncertaintyThresholdRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 放行需同时满足既有偏差判定与合成不确定度不超过仪器当前阈值；
 * 放行时在行锁内读取一致预算并固化快照（分量、计算口径、阈值版本与放行结论）。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final UncertaintyComponentRepository components;
    private final UncertaintyThresholdRepository thresholds;
    private final IdempotencyService idempotency;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          UncertaintyComponentRepository components,
                          UncertaintyThresholdRepository thresholds,
                          IdempotencyService idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.components = components;
        this.thresholds = thresholds;
        this.idempotency = idempotency;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人、
     * 仪器已设置不确定度阈值且合成不确定度不超过阈值。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效。
     * requestId 非空时启用幂等：同参重放返回首次快照，异参 409，失败不占键。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String requestId, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }
        List<String> sortedKeys = orderedKeys.stream().sorted().toList();

        if (requestId == null || requestId.isBlank()) {
            return doRelease(sortedKeys, orderedKeys, releaser);
        }
        String id = Inputs.requireText(requestId, "requestId");
        List<String> fields = new ArrayList<>(sortedKeys);
        return idempotency.execute(id, "RELEASE_MEASUREMENTS", releaser, fields, 200,
                () -> doRelease(sortedKeys, orderedKeys, releaser));
    }

    private ReleaseResponse doRelease(List<String> sortedKeys, List<String> orderedKeys, String releaser) {
        List<ItemFailure> failures = new ArrayList<>();
        List<ApprovedRelease> approved = new ArrayList<>();
        for (String key : sortedKeys) {
            var locked = measurements.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> reasons = new ArrayList<>();
            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() != MeasurementStatus.PENDING) {
                reasons.add("NOT_PENDING");
            }
            if (!measurement.passed()) {
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

            // 在行锁内读取一致预算：合成不确定度（平方和开根，4 位小数）不得超过仪器当前阈值
            List<UncertaintyComponent> budget = components.findByMeasurementId(measurement.id());
            BigDecimal combined = UncertaintyMath.combine(
                    budget.stream().map(UncertaintyComponent::componentValue).toList());
            UncertaintyThreshold threshold = thresholds.findCurrent(measurement.instrumentId())
                    .orElse(null);
            if (threshold == null) {
                reasons.add("THRESHOLD_MISSING");
            } else if (combined.compareTo(threshold.maxUncertainty()) > 0) {
                reasons.add("UNCERTAINTY_EXCEEDED");
            }

            if (reasons.isEmpty()) {
                List<UncertaintyComponentView> views = budget.stream()
                        .map(c -> new UncertaintyComponentView(c.componentName(),
                                DtoMapper.format(c.componentValue())))
                        .toList();
                approved.add(new ApprovedRelease(measurement, combined, threshold,
                        ComponentSnapshots.toJson(views)));
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (ApprovedRelease item : approved) {
            measurements.markReleased(item.measurement().id());
            releases.insert(batchId, item.measurement().id(), releaser, releasedAt,
                    item.combinedUncertainty(), item.threshold().version(),
                    item.threshold().maxUncertainty(), item.measurement().measurementVersion(),
                    UncertaintyMath.CALC_SPEC, item.componentsSnapshot());
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }

    /** 通过校验、等待提交的放行项及其预算快照。 */
    private record ApprovedRelease(Measurement measurement, BigDecimal combinedUncertainty,
                                   UncertaintyThreshold threshold, String componentsSnapshot) {
    }
}
