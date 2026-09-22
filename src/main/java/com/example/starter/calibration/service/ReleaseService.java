package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import com.example.starter.calibration.api.dto.VersionedReleaseResponse;
import com.example.starter.calibration.api.dto.VersionedReleasedItem;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 批量放行服务：每批 1～50 条，整批原子生效；任一项不满足条件则整批拒绝（409）并返回各项原因。
 * 旧入口（仅测量键）只对从未修订（最新版本仍为第 1 版）的测量可用；
 * 已有修订的键必须走版本化放行入口并显式指定 revision。
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
     * 旧批量放行入口（仅按测量键）。从未修订的键保持原语义；
     * 已有修订的键整批拒绝并对该项返回 REVISION_REQUIRED，要求显式版本放行。
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
            var locked = measurements.findLatestByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement latest = locked.get();
            if (latest.revision() > 1) {
                failures.add(new ItemFailure(key, List.of("REVISION_REQUIRED")));
                continue;
            }
            List<String> reasons = evaluate(latest, releaser);
            if (reasons.isEmpty()) {
                approved.add(latest);
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
     * 版本化批量放行。每项必须正是该键最新 PENDING 版本、计算合格、证书未撤销、放行人不同于提交人。
     * 任一失败整批拒绝并返回逐项（键+版本）原因；旧版本号一律以 REVISION_MISMATCH 拒绝，不得放行过期版本。
     */
    @Transactional
    public VersionedReleaseResponse releaseVersions(List<VersionedReleaseItem> items, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("批量放行条数必须为 1～" + MAX_BATCH_SIZE);
        }
        List<VersionedReleaseItem> ordered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (VersionedReleaseItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("放行项不能为空");
            }
            String key = Inputs.requireText(item.measurementKey(), "measurementKey");
            Integer revision = item.revision();
            if (revision == null || revision < 1) {
                throw ApiException.badRequest("revision 必须为不小于 1 的整数");
            }
            if (!seen.add(key)) {
                throw ApiException.badRequest("批量放行包含重复测量键: " + key);
            }
            ordered.add(new VersionedReleaseItem(key, revision));
        }

        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> approved = new ArrayList<>();
        List<VersionedReleaseItem> approvedItems = new ArrayList<>();
        for (VersionedReleaseItem item : ordered.stream()
                .sorted(Comparator.comparing(VersionedReleaseItem::measurementKey)).toList()) {
            String key = item.measurementKey();
            var locked = measurements.findLatestByKeyForUpdate(key);
            if (locked.isEmpty()) {
                failures.add(new ItemFailure(key, item.revision(), List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement latest = locked.get();
            List<String> reasons = new ArrayList<>();
            if (!item.revision().equals(latest.revision())) {
                reasons.add("REVISION_MISMATCH");
            } else {
                reasons.addAll(evaluate(latest, releaser));
            }
            if (reasons.isEmpty()) {
                approved.add(latest);
                approvedItems.add(item);
            } else {
                failures.add(new ItemFailure(key, item.revision(), reasons));
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
        List<VersionedReleasedItem> released = approvedItems.stream()
                .map(i -> new VersionedReleasedItem(i.measurementKey(), i.revision()))
                .toList();
        return new VersionedReleaseResponse(batchId, releaser, releasedAt, released);
    }

    /**
     * 评估单个最新版本的放行条件（调用方须已持有该键指针行锁）：
     * PENDING、合格、证书未撤销（证书行锁与撤销并发互斥）、放行人不同于提交人。
     */
    private List<String> evaluate(Measurement latest, String releaser) {
        List<String> reasons = new ArrayList<>();
        if (latest.status() == MeasurementStatus.RELEASED) {
            reasons.add("ALREADY_RELEASED");
        } else if (latest.status() != MeasurementStatus.PENDING) {
            reasons.add("NOT_PENDING");
        }
        if (!latest.passed()) {
            reasons.add("NOT_PASSED");
        }
        Certificate cert = certificates.findByIdForUpdate(latest.certificateId())
                .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + latest.certificateId()));
        if (cert.revoked()) {
            reasons.add("CERTIFICATE_REVOKED");
        }
        if (latest.submittedBy().equals(releaser)) {
            reasons.add("SAME_ACTOR");
        }
        return reasons;
    }
}
