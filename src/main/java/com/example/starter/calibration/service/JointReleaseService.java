package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.calibration.api.ApiException;
import com.example.starter.calibration.api.ItemFailure;
import com.example.starter.calibration.api.JointBatchRejectedException;
import com.example.starter.calibration.api.dto.JointReleaseDetailResponse;
import com.example.starter.calibration.api.dto.JointReleaseItemResponse;
import com.example.starter.calibration.api.dto.JointReleaseRequest;
import com.example.starter.calibration.api.dto.JointReleaseResponse;
import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.JointReleaseBatch;
import com.example.starter.calibration.model.JointReleaseItem;
import com.example.starter.calibration.model.Measurement;
import com.example.starter.calibration.model.MeasurementStatus;
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.JointReleaseRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;

/**
 * 跨仪器联合批次放行服务：每批 2～20 条，可混合不同仪器，整批原子生效。
 *
 * <p>校验规则与既有同仪器批量放行共享（{@link ReleaseChecks}），但失败语义不同：
 * 校验不通过整批 422 并逐条返回原因；批次校验通过后、提交前被并发修改
 * （测量被其他批次放行、关联证书被撤销）则整批 409，不产生部分放行。
 *
 * <p>幂等：jointBatchKey 全局唯一，仅成功放行占键；同键同参（测量集合换序视为同参）
 * 重放首次响应快照，同键异参 409。
 */
@Service
public class JointReleaseService {

    /** 单批最小条数。 */
    static final int MIN_BATCH_SIZE = 2;
    /** 单批最大条数。 */
    static final int MAX_BATCH_SIZE = 20;

    private final MeasurementRepository measurements;
    private final CertificateRepository certificates;
    private final ReleaseRepository releases;
    private final JointReleaseRepository jointReleases;

    public JointReleaseService(MeasurementRepository measurements,
                               CertificateRepository certificates,
                               ReleaseRepository releases,
                               JointReleaseRepository jointReleases) {
        this.measurements = measurements;
        this.certificates = certificates;
        this.releases = releases;
        this.jointReleases = jointReleases;
    }

    /**
     * 联合批次原子放行。两阶段执行：
     * 阶段一基于当前快照逐条校验，失败整批 422；
     * 阶段二在测量行锁（按测量键字典序）与证书行锁（按证书 ID 升序）内复核，
     * 发现并发修改整批 409，保证与证书撤销、其他联合批次按事务提交顺序裁决。
     */
    @Transactional
    public JointReleaseResponse release(JointReleaseRequest request, String actor) {
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String jointBatchKey = Inputs.requireText(request == null ? null : request.jointBatchKey(),
                "jointBatchKey");
        List<String> keys = request.keys();
        if (keys == null || keys.size() < MIN_BATCH_SIZE || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("联合批次放行条数必须为 " + MIN_BATCH_SIZE + "～" + MAX_BATCH_SIZE);
        }
        List<String> orderedKeys = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(orderedKeys);
        if (distinct.size() != orderedKeys.size()) {
            throw ApiException.badRequest("联合批次放行包含重复测量键");
        }
        List<String> sortedKeys = orderedKeys.stream().sorted().toList();
        String fingerprint = releaser + "\n" + String.join(",", sortedKeys);

        // 幂等：已占键时同参重放首次响应快照，异参 409
        Optional<JointReleaseBatch> existing = jointReleases.findBatchByKey(jointBatchKey);
        if (existing.isPresent()) {
            return resolveReplay(existing.get(), fingerprint);
        }

        // 事务内先占键：并发同键请求在唯一键上串行，败者重放裁决；
        // 后续任何失败（422/409）回滚事务即释放键，失败不占键。
        // 放行时刻截断到微秒，与 DATETIME(6) 存储精度一致，保证同键重放响应快照逐字节相同
        Instant releasedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        JointReleaseBatch batch = new JointReleaseBatch(
                jointBatchKey, fingerprint, releaser, releasedAt, sortedKeys.size());
        try {
            jointReleases.insertBatch(batch);
        } catch (DuplicateKeyException ex) {
            return resolveReplay(jointReleases.findBatchByKey(jointBatchKey).orElseThrow(), fingerprint);
        }

        // 阶段一：无锁快照校验，任一不满足整批 422
        List<ItemFailure> failures = new ArrayList<>();
        List<Measurement> candidates = new ArrayList<>();
        for (String key : sortedKeys) {
            Optional<Measurement> found = measurements.findByKey(key);
            if (found.isEmpty()) {
                failures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Measurement measurement = found.get();
            Certificate cert = certificates.findById(measurement.certificateId())
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + measurement.certificateId()));
            List<String> reasons = ReleaseChecks.reasons(measurement, cert, releaser);
            if (reasons.isEmpty()) {
                candidates.add(measurement);
            } else {
                failures.add(new ItemFailure(key, reasons));
            }
        }
        if (!failures.isEmpty()) {
            throw new JointBatchRejectedException(failures);
        }

        // 阶段二：行锁内复核，校验通过后发生的并发修改整批 409
        for (String key : sortedKeys) {
            Measurement locked = measurements.findByKeyForUpdate(key)
                    .orElseThrow(() -> ApiException.conflict("CONCURRENT_RELEASE_CONFLICT",
                            "测量在校验后被并发修改: " + key));
            if (locked.status() != MeasurementStatus.PENDING) {
                throw ApiException.conflict("CONCURRENT_RELEASE_CONFLICT",
                        "测量在校验后已被其他批次放行: " + key);
            }
        }
        List<Long> certIds = candidates.stream().map(Measurement::certificateId)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)).stream().toList();
        for (Long certId : certIds) {
            Certificate cert = certificates.findByIdForUpdate(certId)
                    .orElseThrow(() -> ApiException.conflict("CERTIFICATE_MISSING",
                            "测量关联的证书不存在: " + certId));
            if (cert.revoked()) {
                throw ApiException.conflict("CONCURRENT_CERTIFICATE_REVOKED",
                        "关联证书在校验后已被撤销: " + certId);
            }
        }

        // 写入不可变联合放行明细、流转测量状态、追加各测量放行历史
        int sort = 0;
        for (Measurement measurement : candidates) {
            jointReleases.insertItem(new JointReleaseItem(0L, jointBatchKey, sort++,
                    measurement.id(), measurement.measurementKey(),
                    measurement.certificateId(), measurement.computedValue()));
            measurements.markReleased(measurement.id());
            releases.insert(jointBatchKey, measurement.id(), releaser, releasedAt);
        }
        return new JointReleaseResponse(jointBatchKey, releaser, releasedAt, sortedKeys);
    }

    /**
     * 联合放行记录及按批次的测量放行明细查询；不存在返回 404，明细按测量键字典序稳定排序。
     */
    @Transactional(readOnly = true)
    public JointReleaseDetailResponse detail(String jointBatchKey) {
        String key = Inputs.requireText(jointBatchKey, "jointBatchKey");
        JointReleaseBatch batch = jointReleases.findBatchByKey(key)
                .orElseThrow(() -> ApiException.notFound("联合放行批次不存在: " + key));
        List<JointReleaseItemResponse> items = jointReleases.findItemsByBatchKey(key).stream()
                .map(JointReleaseService::toItemResponse)
                .toList();
        return new JointReleaseDetailResponse(batch.jointBatchKey(), batch.releasedBy(),
                batch.releasedAt(), items);
    }

    /**
     * 同键重放裁决：参数指纹一致返回首次响应快照，否则 409。
     */
    private JointReleaseResponse resolveReplay(JointReleaseBatch stored, String fingerprint) {
        if (!stored.requestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("JOINT_BATCH_KEY_CONFLICT",
                    "联合批次键已存在且请求参数不同: " + stored.jointBatchKey());
        }
        List<String> released = jointReleases.findItemsByBatchKey(stored.jointBatchKey()).stream()
                .map(JointReleaseItem::measurementKey)
                .toList();
        return new JointReleaseResponse(stored.jointBatchKey(), stored.releasedBy(),
                stored.releasedAt(), released);
    }

    private static JointReleaseItemResponse toItemResponse(JointReleaseItem item) {
        return new JointReleaseItemResponse(item.measurementId(), item.measurementKey(),
                item.certificateId(), DtoMapper.format(item.computedValue()));
    }
}
