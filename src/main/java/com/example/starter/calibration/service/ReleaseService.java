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
import com.example.starter.calibration.api.dto.ReleaseVersionsRequest;
import com.example.starter.calibration.api.dto.ReleaseVersionsResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 旧入口仅支持从未修订的测量键；已修订的键须走版本化放行入口并显式指定修订号。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 原子批量放行（旧入口，仅从未修订的测量键）。每条结果必须：处于待放行、判定合格、
     * 证书仍有效、放行人不同于提交人；已有修订的键返回整批 409 并要求显式版本。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效。
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
            // 锁定该键全部版本行（与修订互斥）；锁等待后重新读取最新已提交状态
            List<Measurement> locked = measurements.findAllByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<Measurement> versions = measurements.findHistory(key);
            Measurement measurement = versions.stream()
                    .filter(Measurement::isLatest).findFirst().orElseThrow();
            if (versions.size() > 1) {
                failures.add(new ItemFailure(key, List.of("REVISION_REQUIRED")));
                continue;
            }
            List<String> reasons = checkReleasable(measurement, releaser);
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

    /**
     * 版本化原子批量放行。每项必须正是该键最新版本、处于待放行、判定合格、证书仍有效、
     * 放行人不同于提交人；任一失败整批拒绝（409）并逐项返回原因。
     * 行锁按测量键字典序获取；与修订、证书撤销并发时按事务提交顺序裁决，不放行过期版本。
     */
    @Transactional
    public ReleaseVersionsResponse releaseVersions(List<ReleaseVersionsRequest.Item> items, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<ReleaseVersionsRequest.Item> ordered = items.stream().map(item -> {
            String key = Inputs.requireText(item.measurementKey(), "items[].measurementKey");
            if (item.revision() == null || item.revision() < 1) {
                throw ApiException.badRequest("items[].revision 必须为正整数");
            }
            return new ReleaseVersionsRequest.Item(key, item.revision());
        }).toList();
        Set<String> distinct = new HashSet<>();
        for (ReleaseVersionsRequest.Item item : ordered) {
            if (!distinct.add(item.measurementKey())) {
                throw ApiException.badRequest("批量放行包含重复测量键");
            }
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        for (ReleaseVersionsRequest.Item item : ordered.stream()
                .sorted(java.util.Comparator.comparing(ReleaseVersionsRequest.Item::measurementKey))
                .toList()) {
            var locked = measurements.findByKeyAndRevisionForUpdate(item.measurementKey(), item.revision());
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(item.measurementKey(), List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> reasons = new ArrayList<>();
            if (!measurement.isLatest()) {
                reasons.add("NOT_LATEST");
            }
            reasons.addAll(checkReleasable(measurement, releaser));
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(item.measurementKey(), reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        List<ReleaseVersionsResponse.Item> released = new ArrayList<>();
        for (Measurement measurement : approved) {
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
            released.add(new ReleaseVersionsResponse.Item(measurement.measurementKey(), measurement.revision()));
        }
        return new ReleaseVersionsResponse(batchId, releaser, releasedAt, released);
    }

    /**
     * 校验单条测量的放行前置条件（状态、合格、证书有效、放行人与提交人不同）。
     */
    private List<String> checkReleasable(Measurement measurement, String releaser) {
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
        return reasons;
    }
}
