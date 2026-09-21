package com.example.starter.calibration.service;

import com.example.starter.calibration.domain.Certificate;
import com.example.starter.calibration.domain.Measurement;
import com.example.starter.calibration.domain.MeasurementStatus;
import com.example.starter.calibration.domain.ReleaseRecord;
import com.example.starter.calibration.error.ApiException;
import com.example.starter.calibration.repository.CertificateRepository;
import com.example.starter.calibration.repository.MeasurementRepository;
import com.example.starter.calibration.repository.ReleaseRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一条目校验失败则整批拒绝并返回各项原因。
 */
@Service
public class ReleaseService {

    /** 单批最大条目数。 */
    public static final int MAX_BATCH_SIZE = 50;

    private final MeasurementRepository measurementRepository;
    private final CertificateRepository certificateRepository;
    private final ReleaseRecordRepository releaseRecordRepository;
    private final Clock clock;

    public ReleaseService(
            MeasurementRepository measurementRepository,
            CertificateRepository certificateRepository,
            ReleaseRecordRepository releaseRecordRepository,
            Clock clock) {
        this.measurementRepository = measurementRepository;
        this.certificateRepository = certificateRepository;
        this.releaseRecordRepository = releaseRecordRepository;
        this.clock = clock;
    }

    /**
     * 原子批量放行。每条结果必须处于待放行、判定合格、证书仍有效，且放行人不同于提交人；
     * 任一项失败则整批拒绝。证书撤销与本方法通过对证书行加锁按事务提交顺序串行化。
     *
     * @param measurementIds 测量 ID 列表，1～50 条且不重复
     * @param actor          放行人（来自 X-Actor-Id）
     * @return 放行结果
     */
    @Transactional
    public ReleaseResult releaseBatch(List<Long> measurementIds, String actor) {
        InputValidation.requireNonBlank("X-Actor-Id", actor);
        if (measurementIds == null || measurementIds.isEmpty()
                || measurementIds.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest(
                    "INVALID_BATCH_SIZE", "每批放行条数必须在 1～" + MAX_BATCH_SIZE + " 之间");
        }
        Set<Long> distinctIds = new LinkedHashSet<>(measurementIds);
        if (distinctIds.size() != measurementIds.size()) {
            throw ApiException.badRequest("DUPLICATE_IDS", "同一批次内测量 ID 重复");
        }

        // 按 ID 升序逐条加行锁，避免并发批次间死锁。
        List<Long> sortedIds = distinctIds.stream().sorted().toList();
        List<Measurement> locked = new ArrayList<>(sortedIds.size());
        List<ApiException.ItemReason> notFound = new ArrayList<>();
        for (Long id : sortedIds) {
            measurementRepository.findByIdForUpdate(id)
                    .ifPresentOrElse(
                            locked::add,
                            () -> notFound.add(new ApiException.ItemReason(
                                    id, "MEASUREMENT_NOT_FOUND", "测量不存在: " + id)));
        }
        if (!notFound.isEmpty()) {
            throw new ApiException(404, "MEASUREMENT_NOT_FOUND", "批次中存在不存在的测量", notFound);
        }

        List<ApiException.ItemReason> failures = new ArrayList<>();
        for (Measurement measurement : locked) {
            for (String reason : checkReleasable(measurement, actor)) {
                failures.add(new ApiException.ItemReason(
                        measurement.id(), reason, reasonMessage(reason, measurement, actor)));
            }
        }
        // 证书行按证书 ID 升序加锁，避免并发批次间死锁；无论测量级校验是否通过都加锁，
        // 保证与撤销事务按事务提交顺序串行化。
        List<Long> certificateIds = locked.stream()
                .map(Measurement::certificateId)
                .distinct()
                .sorted()
                .toList();
        Map<Long, Certificate> certificates = new LinkedHashMap<>();
        for (Long certificateId : certificateIds) {
            certificates.put(certificateId,
                    certificateRepository.findByIdForUpdate(certificateId).orElse(null));
        }
        for (Measurement measurement : locked) {
            Certificate certificate = certificates.get(measurement.certificateId());
            if (certificate == null) {
                failures.add(new ApiException.ItemReason(
                        measurement.id(), "CERTIFICATE_NOT_FOUND",
                        reasonMessage("CERTIFICATE_NOT_FOUND", measurement, actor)));
            } else if (certificate.revoked()) {
                failures.add(new ApiException.ItemReason(
                        measurement.id(), "CERTIFICATE_REVOKED",
                        reasonMessage("CERTIFICATE_REVOKED", measurement, actor)));
            }
        }
        if (!failures.isEmpty()) {
            throw new ApiException(409, "RELEASE_REJECTED", "批次校验失败，整批拒绝", failures);
        }

        Instant releasedAt = Instant.now(clock);
        String batchId = UUID.randomUUID().toString();
        List<ReleaseRecord> records = new ArrayList<>(locked.size());
        for (Measurement measurement : locked) {
            boolean updated = measurementRepository.markReleased(measurement.id(), actor, releasedAt);
            if (!updated) {
                // 行锁下理论上不可达；兜底保证不产生部分成功。
                throw ApiException.conflict(
                        "RELEASE_STATE_CHANGED", "测量状态已变化: " + measurement.id());
            }
            records.add(new ReleaseRecord(
                    0L,
                    measurement.id(),
                    certificates.get(measurement.certificateId()).id(),
                    actor,
                    releasedAt,
                    batchId));
        }
        List<ReleaseRecord> persisted = releaseRecordRepository.insertAll(records);
        return new ReleaseResult(batchId, actor, releasedAt, persisted);
    }

    private List<String> checkReleasable(Measurement measurement, String actor) {
        List<String> reasons = new ArrayList<>();
        if (measurement.status() != MeasurementStatus.PENDING_RELEASE) {
            reasons.add("NOT_PENDING_RELEASE");
        }
        if (!measurement.passed()) {
            reasons.add("NOT_PASSED");
        }
        if (measurement.submittedBy().equals(actor)) {
            reasons.add("ACTOR_EQUALS_SUBMITTER");
        }
        return reasons;
    }

    private String reasonMessage(String reason, Measurement measurement, String actor) {
        return switch (reason) {
            case "NOT_PENDING_RELEASE" -> "测量不处于待放行状态: " + measurement.id();
            case "NOT_PASSED" -> "测量判定不合格: " + measurement.id();
            case "ACTOR_EQUALS_SUBMITTER" -> "放行人 " + actor + " 与提交人相同: " + measurement.id();
            case "CERTIFICATE_REVOKED" -> "证书已撤销: " + measurement.certificateId();
            case "CERTIFICATE_NOT_FOUND" -> "证书不存在: " + measurement.certificateId();
            default -> reason;
        };
    }

    /**
     * 放行结果。
     *
     * @param batchId    批次 ID
     * @param releasedBy 放行人
     * @param releasedAt 放行时刻（UTC）
     * @param records    本批写入的放行记录
     */
    public record ReleaseResult(
            String batchId, String releasedBy, Instant releasedAt, List<ReleaseRecord> records) {
    }
}
