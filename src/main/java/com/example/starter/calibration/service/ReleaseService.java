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
import com.example.starter.calibration.model.DriftArchive;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementMode;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.DriftArchiveRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 放行核对全部证据：原证书与（参考漂移模式的）两端漂移档案，任一失效整批不放。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final DriftArchiveRepository archives;
    private final ReleaseRepository releases;
    private final RequestDedupService dedup;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          DriftArchiveRepository archives,
                          ReleaseRepository releases,
                          RequestDedupService dedup) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.archives = archives;
        this.releases = releases;
        this.dedup = dedup;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、（漂移模式）两端档案未撤销、
     * 放行人不同于提交人。行锁按测量键字典序获取，避免并发批次间死锁；
     * 证书与档案行锁使撤销与放行按事务提交顺序生效。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor,
                                   String requestId, String expectedVersion) {
        List<String> identityParams = keys == null ? List.of() : new ArrayList<>(keys);
        return dedup.execute(requestId, "RELEASE_BATCH", "release",
                actor, expectedVersion, identityParams,
                ReleaseResponse.class,
                () -> doRelease(keys, actor));
    }

    private ReleaseResponse doRelease(List<String> keys, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
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
            if (measurement.mode() == MeasurementMode.DRIFT_REF) {
                List<Long> refIds = List.of(measurement.refArchiveId0(), measurement.refArchiveId1())
                        .stream().sorted().toList();
                for (Long refId : refIds) {
                    DriftArchive archive = archives.findByIdForUpdate(refId)
                            .orElseThrow(() -> ApiException.conflict("DRIFT_ARCHIVE_MISSING",
                                    "测量关联的漂移档案不存在: " + refId));
                    if (archive.revoked()) {
                        reasons.add("REFERENCE_REVOKED");
                        break;
                    }
                }
            }
            if (measurement.submittedBy().equals(releaser)) {
                reasons.add("SAME_ACTOR");
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
