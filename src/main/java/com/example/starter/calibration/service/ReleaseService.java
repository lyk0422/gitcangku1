package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
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

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人。
     * 合格核验使用提交时冻结的精确值、k 与量化值（配置换版不回算旧结果）：
     * 精确值与量化值（若已量化）均须落在原合格闭区间；越界待审（无冻结精确值）不可放行。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效。
     * 任一失败整批无放行记录；含越界待审项时整批 422，其余校验失败整批 409。
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
            if (measurement.computedValue() == null) {
                // 越界待审：无冻结精确值，不可放行
                reasons.add("OUT_OF_RANGE");
            } else if (!frozenValuesInInterval(measurement)) {
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
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            boolean outOfRange = failures.stream()
                    .anyMatch(f -> f.reasons().contains("OUT_OF_RANGE"));
            throw new BatchRejectedException(failures,
                    outOfRange ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT);
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
     * 用提交时冻结的值复核合格性：精确值落在原合格闭区间；若已量化（冻结了 k 与量化值），
     * 量化值也须落在同一闭区间。配置换版不影响本复核，因为只读取测量上冻结的值。
     */
    private static boolean frozenValuesInInterval(Measurement measurement) {
        boolean exactIn = measurement.computedValue().compareTo(measurement.lowerLimit()) >= 0
                && measurement.computedValue().compareTo(measurement.upperLimit()) <= 0;
        if (!exactIn) {
            return false;
        }
        if (measurement.quantizedValue() == null) {
            return true;
        }
        return measurement.quantizedValue().compareTo(measurement.lowerLimit()) >= 0
                && measurement.quantizedValue().compareTo(measurement.upperLimit()) <= 0;
    }
}
