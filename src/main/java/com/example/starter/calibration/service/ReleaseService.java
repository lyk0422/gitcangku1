package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Clock;
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
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.repo.BudgetRepository;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 放行条件在既有偏差判定之外，要求读取到的一致预算的合成不确定度不超过其固化阈值；
 * 预算修订与放行在测量行锁上按事务提交顺序裁决。放行历史固化预算、口径与阈值版本快照。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final BudgetRepository budgets;
    private final Clock clock;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          BudgetRepository budgets,
                          Clock clock) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.budgets = budgets;
        this.clock = clock;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人；
     * 已登记不确定度预算时，合成不确定度（RSS，未舍入存储值）不得超过预算固化阈值（含端点）。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效；
     * 测量行锁同时使预算修订与放行互斥，保证放行读取的预算与放行结论一致。
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

            UncertaintyBudget budget = null;
            if (measurement.currentBudgetId() != null) {
                budget = budgets.findById(measurement.currentBudgetId())
                        .orElseThrow(() -> ApiException.conflict("BUDGET_MISSING",
                                "测量当前预算版本不存在: " + measurement.currentBudgetId()));
                BigDecimal combined = budget.combinedUncertainty();
                if (!UncertaintyCalculator.withinThreshold(combined, budget.thresholdValue())) {
                    reasons.add("UNCERTAINTY_EXCEEDED");
                }
            }

            if (reasons.isEmpty()) {
                approved.add(new ApprovedItem(measurement, budget));
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = clock.instant();
        for (ApprovedItem item : approved) {
            Measurement measurement = item.measurement();
            UncertaintyBudget budget = item.budget();
            measurements.markReleased(measurement.id());
            releases.insert(new ReleaseRepository.ReleaseSnapshot(
                    batchId, measurement.id(), releaser, releasedAt,
                    budget == null ? null : budget.id(),
                    budget == null ? null : budget.method(),
                    budget == null ? null : budget.combinedUncertainty(),
                    budget == null ? null : budget.thresholdId(),
                    budget == null ? null : budget.thresholdVersion(),
                    budget == null ? null : budget.thresholdValue()));
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }

    /**
     * 通过全部校验的待放行项及其读取到的一致预算版本（无预算为 null，快照列写 null）。
     */
    private record ApprovedItem(Measurement measurement, UncertaintyBudget budget) {
    }
}
