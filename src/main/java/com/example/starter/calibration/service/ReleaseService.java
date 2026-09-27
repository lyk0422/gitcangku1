package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.BatchRejectedException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.dto.ReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.InstrumentThreshold;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.ComponentRepository;
import com.example.starter.calibration.repo.ComponentRepository.ComponentValue;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.example.starter.calibration.repo.ThresholdRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 放行需同时满足既有偏差判定与合成不确定度不超过仪器当前阈值；
 * 放行时固化合成不确定度、阈值取值与版本、计算口径，后续阈值修订不改写快照。
 */
@Service
public class ReleaseService {

    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final ComponentRepository components;
    private final ThresholdRepository thresholds;
    private final IdempotencyExecutor idempotency;
    private final TransactionTemplate transactions;

    public ReleaseService(MeasurementRepository measurements,
                          CertificateRepository certificates,
                          ReleaseRepository releases,
                          ComponentRepository components,
                          ThresholdRepository thresholds,
                          IdempotencyExecutor idempotency,
                          PlatformTransactionManager transactionManager) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.components = components;
        this.thresholds = thresholds;
        this.idempotency = idempotency;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 批量放行入口。requestId 为空时直接事务执行；提供时按幂等处理：
     * 同参重放返回首次快照，异参 409，失败（含整批拒绝）不占键。
     */
    public ReleaseResponse release(List<String> keys, String actor, String requestId) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        List<String> orderedKeys = validateKeys(keys);
        if (requestId == null || requestId.isBlank()) {
            return transactions.execute(status -> doRelease(orderedKeys, releaser));
        }
        String rid = requestId.trim();
        List<String> tokens = new ArrayList<>(List.of("RELEASE", releaser));
        tokens.addAll(orderedKeys.stream().sorted().toList());
        String fingerprint = Fingerprint.of(tokens);
        return idempotency.idempotent(rid, "RELEASE", releaser, fingerprint, ReleaseResponse.class,
                () -> doRelease(orderedKeys, releaser));
    }

    private List<String> validateKeys(List<String> keys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("批量放行包含重复测量键");
        }
        return orderedKeys;
    }

    /**
     * 原子批量放行。每条结果必须：处于待放行、判定合格、证书仍有效、放行人不同于提交人、
     * 仪器已登记阈值且合成不确定度不超过阈值。
     * 行锁按测量键字典序获取，避免并发批次间死锁；证书行锁使撤销与放行按事务提交顺序生效；
     * 测量行锁使分量修改与放行按提交顺序裁决，放行读取一致预算。
     */
    private ReleaseResponse doRelease(List<String> orderedKeys, String releaser) {
        List<ItemFailure> failures = new ArrayList<>();
        List<ApprovedMeasurement> approved = new ArrayList<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            var locked = measurements.findByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = locked.get();
            List<String> reasons = new ArrayList<>();
            String detail = null;
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

            List<ComponentValue> budget = components.findByMeasurementId(measurement.id()).stream()
                    .map(c -> new ComponentValue(c.componentName(), c.componentValue()))
                    .toList();
            BigDecimal combined = UncertaintyCalculator.combine(budget);
            Optional<InstrumentThreshold> threshold = thresholds.findCurrent(measurement.instrumentId());
            if (threshold.isEmpty()) {
                reasons.add("THRESHOLD_MISSING");
            } else if (combined.compareTo(threshold.get().threshold()) > 0) {
                reasons.add("UNCERTAINTY_EXCEEDED");
                detail = "actual=" + DtoMapper.format(combined)
                        + ", required<=" + DtoMapper.format(threshold.get().threshold())
                        + ", margin=" + DtoMapper.format(threshold.get().threshold().subtract(combined));
            }

            if (reasons.isEmpty()) {
                approved.add(new ApprovedMeasurement(measurement, combined, threshold.orElseThrow()));
            } else {
                failures.add(new ItemFailure(key, reasons, detail));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }

        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (ApprovedMeasurement item : approved) {
            measurements.markReleased(item.measurement().id());
            releases.insert(batchId, item.measurement().id(), releaser, releasedAt,
                    item.combined(), item.threshold().threshold(), item.threshold().version(),
                    UncertaintyCalculator.FORMULA);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }

    /**
     * 通过放行校验的测量及其放行时固化的不确定度证据。
     *
     * @param measurement 测量记录（已持有行锁）
     * @param combined    合成不确定度（RSS，HALF_UP 4 位小数）
     * @param threshold   放行时仪器当前阈值
     */
    private record ApprovedMeasurement(Measurement measurement, BigDecimal combined,
                                       InstrumentThreshold threshold) {
    }
}
