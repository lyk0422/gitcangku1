package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseItemResponse;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseRecord;
import com.example.starter.calibration.model.ThresholdVersion;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ThresholdVersionRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 放行条件：待放行 + 既有偏差判定合格 + 证书未撤销 + 放行人不同于提交人
 * + 已登记不确定度预算 + 测量时刻存在生效阈值版本 + 合成不确定度（RSS，4 位小数）不超过阈值。
 * 放行时把预算版本、合成不确定度、阈值版本与取值固化进放行历史；后续修订不改写快照。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final UncertaintyComponentRepository components;
    private final ThresholdVersionRepository thresholds;
    private final IdempotencyService idempotency;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          UncertaintyComponentRepository components,
                          ThresholdVersionRepository thresholds,
                          IdempotencyService idempotency) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.components = components;
        this.thresholds = thresholds;
        this.idempotency = idempotency;
    }

    /**
     * 原子批量放行。行锁按测量键字典序获取，避免并发批次间死锁；
     * 分量预算在测量行锁内读取，保证“放行读取一致预算”；证书行锁使撤销与放行按提交顺序生效。
     * 同 requestId 同参重放返回首次响应快照，异参 409，失败不占键。
     */
    @Transactional
    public ReleaseResponse release(List<String> keys, String actor, String requestId) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("keys", orderedKeys.stream().sorted().toList());
        var gate = idempotency.begin(requestId, "RELEASE", releaser, "BATCH_RELEASE", params);

        List<ItemFailure> failures = new ArrayList<>();
        List<ApprovedItem> approved = new ArrayList<>();
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
            if (measurement.submittedBy().equals(releaser)) {
                reasons.add("SAME_ACTOR");
            }

            // 不确定度预算：必须已登记；在测量行锁内读取当前完整预算，避免并发替换读到半成品。
            long budgetVersion = components.currentBudgetVersion(measurement.id());
            List<UncertaintyComponent> budget = budgetVersion == 0
                    ? List.of()
                    : components.findByBudgetVersion(measurement.id(), budgetVersion);
            if (budgetVersion == 0) {
                reasons.add("BUDGET_NOT_REGISTERED");
            }

            // 阈值：测量时刻必须存在生效版本。阈值表只增不改，放行时固化版本 ID 与取值快照。
            ThresholdVersion threshold = thresholds.findEffectiveAt(
                    measurement.instrumentId(), measurement.measuredAt()).orElse(null);
            if (threshold == null) {
                reasons.add("THRESHOLD_NOT_FOUND");
            }

            BigDecimal combined = UncertaintyMath.combined(budget);
            if (budgetVersion > 0 && threshold != null
                    && combined.compareTo(threshold.maxUncertainty()) > 0) {
                reasons.add("UNCERTAINTY_EXCEEDS_THRESHOLD");
            }

            if (reasons.isEmpty()) {
                approved.add(new ApprovedItem(key, measurement, budgetVersion, combined, threshold));
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        Map<String, ApprovedItem> byKey = new LinkedHashMap<>();
        for (ApprovedItem item : approved) {
            byKey.put(item.key(), item);
        }
        for (ApprovedItem item : approved) {
            measurements.markReleased(item.measurement().id());
            releases.insert(batchId, item.measurement().id(), releaser, releasedAt,
                    item.budgetVersion(), item.combined(),
                    item.threshold().id(), item.threshold().maxUncertainty(),
                    ReleaseRecord.METHOD_RSS_HALF_UP_4);
        }
        List<ReleaseItemResponse> items = orderedKeys.stream()
                .map(k -> {
                    ApprovedItem item = byKey.get(k);
                    return new ReleaseItemResponse(k, item.budgetVersion(),
                            DtoMapper.format4(item.combined()),
                            item.threshold().id(),
                            DtoMapper.format(item.threshold().maxUncertainty()),
                            ReleaseRecord.METHOD_RSS_HALF_UP_4);
                })
                .toList();
        ReleaseResponse response = new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys, items);
        idempotency.recordSuccess(gate, 200, response);
        return response;
    }

    /** 校验通过、待提交的放行项及其快照数据。 */
    private record ApprovedItem(String key, Measurement measurement, long budgetVersion,
                               BigDecimal combined, ThresholdVersion threshold) {
    }
}
