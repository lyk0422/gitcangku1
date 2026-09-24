package com.example.starter.calibration.service;

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
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.InterimCheck;
import com.example.starter.calibration.model.IsolationInterval;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.InterimCheckRepository;
import com.example.starter.calibration.repo.IsolationIntervalRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final IsolationIntervalRepository intervals;
    private final InterimCheckRepository checks;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          IsolationIntervalRepository intervals,
                          InterimCheckRepository checks) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.intervals = intervals;
        this.checks = checks;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人，
     * 且测量时刻不落在任何未解除的期间核查追溯隔离区间内。
     * 测量行锁与区间行锁使 FAIL 核查与放行按事务提交顺序裁决：
     * FAIL 先提交则同批放行整批失败；放行先提交则其结果随即被标记 SUSPECT。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }

        // 先一次性按测量 ID 升序锁定全部命中测量行，与 FAIL 核查的区间加锁顺序一致，避免死锁。
        List<String> sortedKeys = orderedKeys.stream().sorted().toList();
        java.util.Map<String, Measurement> lockedByKey = new java.util.HashMap<>();
        for (Measurement measurement : measurements.findByKeysForUpdateOrderedById(sortedKeys)) {
            lockedByKey.put(measurement.measurementKey(), measurement);
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (String key : sortedKeys) {
            Measurement measurement = lockedByKey.get(key);
            if (measurement == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
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
            String blockingCheckKey = null;
            List<IsolationInterval> blockers =
                    intervals.findOpenCoveringForUpdate(measurement.instrumentId(), measurement.measuredAt());
            if (!blockers.isEmpty()) {
                reasons.add("UNDER_INTERIM_ISOLATION");
                blockingCheckKey = checks.findById(blockers.get(0).checkId())
                        .map(InterimCheck::checkKey)
                        .orElse(null);
            }
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, List.copyOf(reasons), blockingCheckKey));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Measurement measurement : approved) {
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }
}
