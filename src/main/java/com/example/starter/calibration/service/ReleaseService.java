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

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.api.dto.ReleaseSnapshotResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.model.ReleaseSnapshot;
import com.example.starter.calibration.model.UncertaintyBudget;
import com.example.starter.calibration.model.UncertaintyComponent;
import com.example.starter.calibration.model.UncertaintyThreshold;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ReleaseSnapshotRepository;
import com.example.starter.calibration.repo.ThresholdRepository;
import com.example.starter.calibration.repo.UncertaintyBudgetRepository;
import com.example.starter.calibration.repo.UncertaintyComponentRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效。
 *
 * <p>放行条件（同时满足）：处于待放行、既有偏差判定合格、证书仍有效、放行人不同于提交人、
 * 仪器在放行时刻存在有效不确定度阈值版本、当前预算的合成不确定度（RSS 四位小数）不超过该阈值。
 * 行锁按测量键字典序获取；放行读取一致预算（同一预算版本）；结果、分量、口径、阈值版本与结论
 * 在放行事务内固化为快照，后续阈值修订/预算修改/测量修订均不改写旧快照。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final ReleaseSnapshotRepository snapshots;
    private final ThresholdRepository thresholds;
    private final UncertaintyBudgetRepository budgets;
    private final UncertaintyComponentRepository components;
    private final BusinessClock clock;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          ReleaseSnapshotRepository snapshots,
                          ThresholdRepository thresholds,
                          UncertaintyBudgetRepository budgets,
                          UncertaintyComponentRepository components,
                          BusinessClock clock) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.snapshots = snapshots;
        this.thresholds = thresholds;
        this.budgets = budgets;
        this.components = components;
        this.clock = clock;
    }

    /**
     * 原子批量放行（实际判定与提交，须由幂等执行器包裹）。
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

        Instant releasedAt = clock.now();
        List<ItemFailure> failures = new ArrayList<>();
        boolean anyStale = false;
        boolean anyThresholdMissing = false;
        List<ApprovedItem> approved = new ArrayList<>();

        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = measurements.findLatestByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> reasons = new ArrayList<>();
            if (measurement.status() == MeasurementStatus.RELEASED) {
                reasons.add("ALREADY_RELEASED");
            } else if (measurement.status() == MeasurementStatus.SUPERSEDED) {
                reasons.add("STALE_MEASUREMENT_VERSION");
                anyStale = true;
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

            // 放行读取一致预算：测量行已加锁，预算修改与放行串行化，此处读到的是提交顺序上的完整预算版本
            int budgetVersion = budgets.maxBudgetVersion(measurement.id());
            List<UncertaintyComponent> budget = budgetVersion == 0
                    ? List.of()
                    : components.findByMeasurementAndVersion(measurement.id(), budgetVersion);
            BigDecimal combined = UncertaintyCalculator.combineComponents(budget);

            // 放行时刻有效的仪器阈值版本（左闭右开）；缺失为 422 语义前置失败
            UncertaintyThreshold threshold = thresholds.findEffectiveAt(measurement.instrumentId(), releasedAt)
                    .orElse(null);
            if (threshold == null) {
                reasons.add("THRESHOLD_MISSING");
                anyThresholdMissing = true;
            } else if (!UncertaintyCalculator.withinThreshold(combined, threshold.thresholdValue())) {
                reasons.add("UNCERTAINTY_EXCEEDS_THRESHOLD");
            }

            if (reasons.isEmpty()) {
                approved.add(new ApprovedItem(key, measurement, budgetVersion, budget, combined, threshold));
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            // 状态码优先级：版本过期 410 > 阈值缺失 422 > 条件不满足 409；任一项失败整批不提交
            HttpStatus status = anyStale ? HttpStatus.GONE
                    : anyThresholdMissing ? HttpStatus.UNPROCESSABLE_ENTITY
                    : HttpStatus.CONFLICT;
            String code = anyStale ? "BATCH_STALE_VERSION"
                    : anyThresholdMissing ? "BATCH_UNPROCESSABLE"
                    : "BATCH_REJECTED";
            throw new BatchRejectedException(status, code,
                    "批量放行被拒绝：存在不满足放行条件的测量", failures);
        }

        // 先验证完整后态，再在一个事务内提交：批次号、放行时间、状态、历史与快照原子写入
        String batchId = UUID.randomUUID().toString();
        List<ReleaseSnapshotResponse> snapshotResponses = new ArrayList<>();
        for (ApprovedItem item : approved) {
            Measurement m = item.measurement();
            measurements.markReleased(m.id());
            releases.insert(batchId, m.id(), releaser, releasedAt);

            ReleaseSnapshot snapshot = new ReleaseSnapshot(
                    0L, batchId, m.id(), m.measurementKey(), m.version(), m.computedValue(), m.passed(),
                    item.budgetVersion(), item.combined(), UncertaintyCalculator.FORMULA,
                    item.threshold().id(), item.threshold().version(), item.threshold().thresholdValue(),
                    true, "APPROVED", releaser, releasedAt, item.budget());
            long snapshotId = snapshots.insert(snapshot);
            for (UncertaintyComponent component : item.budget()) {
                snapshots.insertComponent(snapshotId, component);
            }
            snapshotResponses.add(DtoMapper.toResponse(new ReleaseSnapshot(
                    snapshotId, batchId, m.id(), m.measurementKey(), m.version(), m.computedValue(),
                    m.passed(), item.budgetVersion(), item.combined(), UncertaintyCalculator.FORMULA,
                    item.threshold().id(), item.threshold().version(), item.threshold().thresholdValue(),
                    true, "APPROVED", releaser, releasedAt, item.budget())));
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys, snapshotResponses);
    }

    /**
     * 构造放行请求的规范化指纹字段（操作者由外层加入；键集合排序，含全部影响结果字段）。
     */
    public static Map<String, Object> fingerprintFields(List<String> keys) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("keys", keys == null ? List.of()
                : keys.stream().sorted().map(k -> k == null ? "" : k.trim()).toList());
        return fields;
    }

    /**
     * 某测量业务键全部版本的放行快照历史（按放行时间、快照 ID 升序，只读，不改状态）。
     */
    @Transactional(readOnly = true)
    public List<ReleaseSnapshotResponse> snapshotsByKey(String measurementKey) {
        String key = Inputs.requireText(measurementKey, "measurementKey");
        return snapshots.findByMeasurementKey(key).stream()
                .map(DtoMapper::toResponse)
                .toList();
    }

    /** 校验通过待提交项及其放行时刻读取的一致预算与阈值。 */
    private record ApprovedItem(
            String key,
            Measurement measurement,
            int budgetVersion,
            List<UncertaintyComponent> budget,
            BigDecimal combined,
            UncertaintyThreshold threshold) {
    }
}
