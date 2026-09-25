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
import com.example.starter.calibration.model.ReviewConclusion;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.PeerReviewRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 放行服务：每批 1～50 条整批原子生效（409 携带各项原因）；单条放行门禁不满足返回 422。
 * 放行须同时满足既有判定条件（待放行、合格、证书有效、放行人非提交人）与当前版本有效 PASS 复核。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final PeerReviewRepository reviews;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          PeerReviewRepository reviews) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.reviews = reviews;
    }

    /**
     * 原子批量放行。行锁按测量键字典序获取，避免并发批次间死锁；
     * 证书行锁使撤销与放行按事务提交顺序生效，测量 head 行锁使复核/修订与放行串行裁决。
     * 任一项不满足条件则整批拒绝（409）并返回各项原因（含 REVIEW_GATE_FAILED）。
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

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            var evaluated = evaluate(key, releaser);
            if (evaluated.reasons().isEmpty()) {
                approved.add(evaluated.measurement());
            } else {
                failures.add(new ItemFailure(key, evaluated.reasons()));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        return commit(approved, orderedKeys, releaser);
    }

    /**
     * 单条放行：门禁不满足返回 422 并说明缺少或无效复核等原因；已放行返回 409；不存在 404。
     */
    @Transactional
    public ReleaseResponse releaseOne(String key, String actor) {
        String measurementKey = Inputs.requireText(key, "measurementKey");
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        Measurement measurement = measurements.findByKeyForUpdate(measurementKey)
                .orElseThrow(() -> ApiException.notFound("测量不存在: " + measurementKey));
        if (measurement.status() == MeasurementStatus.RELEASED) {
            throw ApiException.conflict("ALREADY_RELEASED", "测量已放行: " + measurementKey);
        }
        Evaluated evaluated = evaluate(measurementKey, releaser);
        if (!evaluated.reasons().isEmpty()) {
            throw ApiException.unprocessable("GATE_NOT_SATISFIED",
                    "放门禁未满足: " + describeReasons(evaluated.reasons()));
        }
        return commit(List.of(evaluated.measurement()), List.of(measurementKey), releaser);
    }

    private ReleaseResponse commit(List<Measurement> approved, List<String> orderedKeys, String releaser) {
        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Measurement measurement : approved) {
            measurements.updateStatus(measurement.id(), MeasurementStatus.RELEASED);
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }

    /**
     * 在持锁状态下评估单项放行条件，返回失败原因码（保持既有顺序，复核门禁置于末尾）。
     */
    private Evaluated evaluate(String key, String releaser) {
        var locked = measurements.findByKeyForUpdate(key);
        if (locked.isEmpty()) {
            return new Evaluated(null, List.of("MEASUREMENT_NOT_FOUND"));
        }
        Measurement measurement = locked.get();
        List<String> reasons = new ArrayList<>();
        if (measurement.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (measurement.status() != MeasurementStatus.PENDING) {
            // RETURNED（待修订）或其它非待放行状态
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
        boolean validPass = reviews.findValid(key, measurement.version(), ReviewConclusion.PASS).isPresent();
        if (!validPass) {
            reasons.add("REVIEW_GATE_FAILED");
        }
        return new Evaluated(measurement, List.copyOf(reasons));
    }

    private String describeReasons(List<String> reasons) {
        List<String> descriptions = new ArrayList<>();
        for (String reason : reasons) {
            descriptions.add(switch (reason) {
                case "NOT_PENDING" -> "测量不处于待放行状态（可能已被退回修订）";
                case "NOT_PASSED" -> "测量判定不合格";
                case "CERTIFICATE_REVOKED" -> "关联证书已撤销";
                case "SAME_ACTOR" -> "放行人不能是提交人";
                case "REVIEW_GATE_FAILED" -> "缺少或无效的当前版本 PASS 同行复核";
                default -> reason;
            });
        }
        return String.join("；", descriptions);
    }

    private record Evaluated(Measurement measurement, List<String> reasons) {
    }
}
