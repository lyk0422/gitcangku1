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
import com.example.starter.calibration.api.dto.VersionedReleaseItem;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementHead;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：
 * <ul>
 *   <li>旧入口（按键）：每批 1～50 条；从未修订的测量（最新版本仍为第 1 版）保持可用，
 *       已有修订的键整批拒绝并返回 EXPLICIT_VERSION_REQUIRED，要求改用版本化入口。</li>
 *   <li>版本化入口：每项指定 measurementKey 与确切 revision，须正是最新 PENDING 版本。</li>
 * </ul>
 * 整批原子生效；行锁按测量键字典序获取，修订/放行/证书撤销按事务提交顺序裁决。
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
     * 旧的按键批量放行。任一测量已有修订版本（最新版本号 &gt; 1）时整批拒绝，
     * 逐项返回 EXPLICIT_VERSION_REQUIRED；其余条件与版本化入口一致。
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
            MeasurementHead head = measurements.findHeadForUpdate(key).orElse(null);
            if (head == null) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            List<String> reasons = new ArrayList<>();
            if (head.latestRevision() > 1) {
                // 已有修订：旧入口不再受理，必须显式指定版本。
                reasons.add("EXPLICIT_VERSION_REQUIRED");
            }
            Measurement measurement = measurements
                    .findByIdForUpdate(head.latestMeasurementId()).orElseThrow();
            appendVersionChecks(measurement, releaser, reasons);
            if (reasons.isEmpty()) {
                approved.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }
        return commitBatch(approved, orderedKeys, releaser);
    }

    /**
     * 版本化原子批量放行。每项必须：键与版本存在、revision 正是最新版本、PENDING、
     * 计算合格、证书未撤销、放行人与原提交人不同；任一失败整批拒绝并返回逐项原因。
     */
    @Transactional
    public ReleaseResponse releaseVersioned(List<VersionedReleaseItem> items, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<VersionedReleaseItem> ordered = items.stream()
                .map(item -> new VersionedReleaseItem(
                        Inputs.requireText(item.measurementKey(), "measurementKey"),
                        Inputs.requirePositive(item.revision(), "revision")))
                .toList();
        Set<String> distinctKeys = new HashSet<>();
        for (VersionedReleaseItem item : ordered) {
            if (!distinctKeys.add(item.measurementKey())) {
                throw ApiException.badRequest("批量放行包含重复测量键: " + item.measurementKey());
            }
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        List<String> approvedKeys = new ArrayList<>();
        for (VersionedReleaseItem item : ordered.stream()
                .sorted(java.util.Comparator.comparing(VersionedReleaseItem::measurementKey)).toList()) {
            String key = item.measurementKey();
            int revision = item.revision();
            List<String> reasons = new ArrayList<>();

            MeasurementHead head = measurements.findHeadForUpdate(key).orElse(null);
            if (head == null) {
                failures.add(new ItemFailure(key + "#" + revision, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = measurements.findByKeyAndRevision(key, revision).orElse(null);
            if (measurement == null) {
                failures.add(new ItemFailure(key + "#" + revision, List.of("VERSION_NOT_FOUND")));
                continue;
            }
            // 锁定该版本行以串行化状态流转；持 head 行锁后比对最新版本，
            // 阻止放行过期版本（修订已提交时本事务会读到新指针）。
            measurement = measurements.findByIdForUpdate(measurement.id()).orElseThrow();
            if (head.latestRevision() != revision) {
                reasons.add("VERSION_NOT_LATEST");
            }
            appendVersionChecks(measurement, releaser, reasons);
            if (reasons.isEmpty()) {
                approved.add(measurement);
                approvedKeys.add(key);
            } else {
                failures.add(new ItemFailure(key + "#" + revision, reasons));
            }
        }

        if (!failures.isEmpty()) {
            throw new BatchRejectedException(failures);
        }
        return commitBatch(approved, ordered.stream().map(VersionedReleaseItem::measurementKey).toList(),
                releaser);
    }

    /**
     * 校验单个测量版本：状态 PENDING、计算合格、证书未撤销（行锁）、放行人不同于原提交人。
     */
    private void appendVersionChecks(Measurement measurement, String releaser, List<String> reasons) {
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
    }

    /**
     * 提交放行：逐行置 RELEASED 并写同一批次的放行历史。
     */
    private ReleaseResponse commitBatch(List<Measurement> approved, List<String> orderedKeys,
                                        String releaser) {
        String batchId = UUID.randomUUID().toString();
        Instant releasedAt = Instant.now();
        for (Measurement measurement : approved) {
            measurements.markReleased(measurement.id());
            releases.insert(batchId, measurement.id(), releaser, releasedAt);
        }
        return new ReleaseResponse(batchId, releaser, releasedAt, orderedKeys);
    }
}
