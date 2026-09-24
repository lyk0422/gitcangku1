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
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
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

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          IsolationIntervalRepository intervals) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.intervals = intervals;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人、
     * 且测量时刻不落入任何未解除的 FAIL 追溯区间。
     * 先按仪器字典序获取仪器级行锁，再按测量键字典序获取行锁，
     * 与期间核查、证书撤销、并发批次按事务提交顺序串行裁决，避免死锁。
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

        // 第一遍（无锁）：确定涉及的仪器，便于按字典序统一加仪器锁。
        List<Measurement> preview = new ArrayList<>();
        List<ItemFailure> missing = new ArrayList<>();
        for (String key : orderedKeys) {
            var found = measurements.findByKey(key);
            if (found.isEmpty()) {
                missing.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
            } else {
                preview.add(found.get());
            }
        }
        preview.stream().map(Measurement::instrumentId).distinct().sorted()
                .forEach(certificates::lockInstrument);

        List<ItemFailure> failures = new ArrayList<>(missing);
        List<Measurement> approved = new ArrayList<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = measurements.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
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
            if (measurement.status() == MeasurementStatus.PENDING) {
                var openIntervals = this.intervals.findOpenAt(
                        measurement.instrumentId(), measurement.measuredAt());
                if (!openIntervals.isEmpty()) {
                    // 区间内待放行结果禁止放行，并指明触发的 checkKey（多个区间时全部列出）。
                    for (var interval : openIntervals) {
                        reasons.add("ISOLATED_BY_CHECK:" + interval.checkKey());
                    }
                }
            }
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons));
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
