package com.example.starter.calibration.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
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
import com.example.starter.calibration.repo.CertificateRepository;
import com.example.starter.calibration.repo.JointReleaseRepository;
import com.example.starter.calibration.repo.MeasurementRepository;
import com.example.starter.calibration.repo.ReleaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 跨仪器联合批次放行服务：2～20 条测量（可来自不同仪器）在一个事务内一致放行。
 * 校验规则与既有同仪器批量放行共享（{@link ReleaseRules}）。
 *
 * <p>裁决流程（单事务，全部测量行先按测量键字典序加锁，再按证书 ID 字典序加锁，避免跨批次死锁）：
 * <ol>
 *   <li>在任何数据库访问之前记录请求进入时刻 entryStart；</li>
 *   <li>对测量行、证书行加 FOR UPDATE 后，在锁内重新读取当前状态、判定结果与证书有效性；</li>
 *   <li>业务失效（待放行/合格/放行人约束不满足、测量不存在、进入前证书已撤销）整批 422 逐条返回原因，
 *       不写入任何记录（失败不占键）；</li>
 *   <li>纯并发改变（关联测量已被其他联合批次放行、或证书撤销时刻晚于 entryStart，即校验通过后才撤销）
 *       整批 409 回滚，不产生部分放行。</li>
 * </ol>
 * 由此证书撤销与放行按事务提交顺序裁决：撤销先提交则放行 409；放行先提交则历史固化、撤销不追溯。
 */
@Service
public class JointReleaseService {

    /** 联合批次最小条数。 */
    static final int MIN_BATCH_SIZE = 2;
    /** 联合批次最大条数。 */
    static final int MAX_BATCH_SIZE = 20;

    private static final ObjectMapper JSON = new ObjectMapper();

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
     * 联合批次一致放行。
     * 任一测量不满足业务条件整批 422 逐条返回原因；并发冲突整批 409；
     * 同 requestId 且同 jointBatchKey、同测量集合（换序视为同参）重放返回首次成功响应快照；异参 409。
     */
    @Transactional
    public JointReleaseResponse release(JointReleaseRequest request, String actor) {
        // 请求进入时刻（任何数据库访问之前）：用于区分“进入前已撤销（业务 422）”
        // 与“进入后放行提交前被并发撤销（冲突 409）”
        Instant entryStart = Instant.now();
        String releaser = Inputs.requireText(actor, "X-Actor-Id");
        String jointBatchKey = Inputs.requireText(request == null ? null : request.jointBatchKey(),
                "jointBatchKey");
        String requestId = Inputs.requireText(request == null ? null : request.requestId(), "requestId");
        List<String> orderedKeys = requireKeys(request == null ? null : request.keys());
        Set<String> keySet = new LinkedHashSet<>(orderedKeys);

        // 幂等：同 requestId 已存在成功批次时，同参回放首次快照，异参 409（失败不占键，故只会查到成功记录）
        JointReleaseBatch existing = jointReleases.findBatchByRequestId(requestId).orElse(null);
        if (existing != null) {
            return replayOrConflict(existing, jointBatchKey, keySet);
        }

        // 加锁读取并裁决：返回可放行测量；业务失效抛 422，纯并发冲突抛 409（同参并发请求回放快照）
        List<Measurement> locked;
        try {
            locked = lockAndDecide(orderedKeys, releaser, entryStart);
        } catch (ApiException ex) {
            if (ex.getStatus() == HttpStatus.CONFLICT) {
                // 并发落败：若胜出者恰为同 requestId 同参请求，按幂等语义回放首次成功快照
                JointReleaseBatch winner = jointReleases.findBatchByRequestId(requestId).orElse(null);
                if (winner != null) {
                    return replayOrConflict(winner, jointBatchKey, keySet);
                }
            }
            throw ex;
        }

        // 截断到微秒：与 DATETIME(6) 存储精度一致，保证首次响应与重放快照的时刻字符串完全相同
        Instant releasedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String snapshot = writeSnapshot(orderedKeys);
        long jointBatchId;
        try {
            jointBatchId = jointReleases.insertBatch(jointBatchKey, requestId, releaser,
                    releasedAt, snapshot, releasedAt);
        } catch (DuplicateKeyException ex) {
            // 并发提交：joint_batch_key 或 request_id 唯一约束落败，以先落地批次为准回放或判冲突
            JointReleaseBatch winner = jointReleases.findBatchByRequestId(requestId)
                    .or(() -> jointReleases.findBatchByJointKey(jointBatchKey))
                    .orElseThrow(() -> ApiException.conflict("JOINT_BATCH_KEY_CONFLICT",
                            "联合批次键或请求键已被占用: " + jointBatchKey));
            return replayOrConflict(winner, jointBatchKey, keySet);
        }

        // 既有单条放行历史共享 release_record，batch_id 直接使用 jointBatchKey 关联，
        // 每条测量自身放行历史与既有单条查询保持兼容。
        for (Measurement measurement : locked) {
            measurements.markReleased(measurement.id());
            releases.insert(jointBatchKey, measurement.id(), releaser, releasedAt);
            jointReleases.insertItem(jointBatchId, jointBatchKey, new JointReleaseItem(
                    0L, jointBatchId, jointBatchKey, measurement.id(), measurement.measurementKey(),
                    measurement.instrumentId(), measurement.certificateId(), measurement.computedValue(),
                    releaser, releasedAt));
        }
        return new JointReleaseResponse(jointBatchKey, requestId, releaser, releasedAt,
                List.copyOf(orderedKeys));
    }

    /**
     * 按 jointBatchKey 查询不可变联合放行记录及按测量键字典序稳定排序的明细；不存在 404。
     */
    @Transactional(readOnly = true)
    public JointReleaseDetailResponse detail(String jointBatchKey) {
        String key = Inputs.requireText(jointBatchKey, "jointBatchKey");
        JointReleaseBatch batch = jointReleases.findBatchByJointKey(key)
                .orElseThrow(() -> ApiException.notFound("联合放行批次不存在: " + key));
        List<JointReleaseItemResponse> items = jointReleases.findItemsByJointBatchId(batch.id()).stream()
                .map(item -> new JointReleaseItemResponse(
                        item.measurementKey(),
                        item.instrumentId(),
                        item.certificateId(),
                        DtoMapper.format(item.computedValue()),
                        item.releasedBy(),
                        item.releasedAt()))
                .toList();
        return new JointReleaseDetailResponse(batch.jointBatchKey(), batch.requestId(),
                batch.releasedBy(), batch.releasedAt(), items);
    }

    /** 校验条数（2～20）与批内唯一性，返回去空白后的请求顺序列表。 */
    private List<String> requireKeys(List<String> keys) {
        if (keys == null || keys.size() < MIN_BATCH_SIZE || keys.size() > MAX_BATCH_SIZE) {
            throw ApiException.badRequest("联合批次放行条数必须为 " + MIN_BATCH_SIZE + "～" + MAX_BATCH_SIZE);
        }
        List<String> ordered = keys.stream().map(k -> Inputs.requireText(k, "keys[]")).toList();
        Set<String> distinct = new HashSet<>(ordered);
        if (distinct.size() != ordered.size()) {
            throw ApiException.badRequest("联合批次放行包含重复测量键");
        }
        return ordered;
    }

    /**
     * 加锁裁决：测量键字典序锁测量行，证书 ID 字典序锁证书行，随后重新读取并逐条评估。
     * 业务失效收集为 422 逐条原因；仅当全部失败项都源于并发改变时整批 409。
     *
     * @param entryStart 请求进入时刻；证书撤销时刻晚于该时刻视为“校验通过后被撤销”的并发冲突
     */
    private List<Measurement> lockAndDecide(List<String> orderedKeys, String releaser,
                                            Instant entryStart) {
        Map<String, Measurement> lockedByKey = new HashMap<>();
        for (String key : orderedKeys.stream().sorted().toList()) {
            Measurement measurement = measurements.findByKeyForUpdate(key).orElse(null);
            if (measurement != null) {
                lockedByKey.put(key, measurement);
            }
        }
        Set<Long> certIds = lockedByKey.values().stream().map(Measurement::certificateId)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
        Map<Long, Certificate> lockedCerts = new HashMap<>();
        for (Long certId : certIds.stream().sorted().toList()) {
            certificates.findByIdForUpdate(certId).ifPresent(cert -> lockedCerts.put(certId, cert));
        }

        List<ItemFailure> businessFailures = new ArrayList<>();
        boolean concurrentChange = false;
        for (String key : orderedKeys) {
            Measurement measurement = lockedByKey.get(key);
            if (measurement == null) {
                // 测量不存在：稳定的业务失效，与既有入口原因码保持一致
                businessFailures.add(new ItemFailure(key, List.of("MEASUREMENT_NOT_FOUND")));
                continue;
            }
            Certificate cert = lockedCerts.get(measurement.certificateId());
            if (cert == null) {
                throw ApiException.conflict("CERTIFICATE_MISSING",
                        "测量关联的证书不存在: " + measurement.certificateId());
            }
            List<String> reasons = ReleaseRules.evaluate(measurement, cert, releaser);
            if (reasons.isEmpty()) {
                continue;
            }

            List<String> businessReasons = new ArrayList<>();
            for (String reason : reasons) {
                if (reason.equals("CERTIFICATE_REVOKED") && isConcurrentRevocation(cert, entryStart)) {
                    // 撤销事务在请求进入后、本事务获锁前提交
                    concurrentChange = true;
                } else if (reason.equals("ALREADY_RELEASED")
                        && jointReleases.countItemsByMeasurementId(measurement.id()) > 0) {
                    // 已被其他联合批次放行：两个联合批次共享同一测量时落败方 409
                    concurrentChange = true;
                } else {
                    businessReasons.add(reason);
                }
            }
            if (!businessReasons.isEmpty()) {
                businessFailures.add(new ItemFailure(key, businessReasons));
            }
        }

        if (!businessFailures.isEmpty()) {
            // 存在与并发无关的业务失效：确定性 422 并逐条返回原因
            throw new JointBatchRejectedException(businessFailures);
        }
        if (concurrentChange) {
            throw ApiException.conflict("JOINT_BATCH_CONCURRENT_CONFLICT",
                    "联合批次校验通过后关联状态发生并发变化，整批拒绝");
        }
        return orderedKeys.stream().map(lockedByKey::get).toList();
    }

    /**
     * 判断证书撤销是否发生在请求进入之后（并发撤销）。
     * revokedAt 缺失或不晚于进入时刻，说明请求进入时证书已撤销，属业务 422。
     */
    private boolean isConcurrentRevocation(Certificate cert, Instant entryStart) {
        Instant revokedAt = cert.revokedAt();
        return revokedAt != null && !revokedAt.isBefore(entryStart);
    }

    /**
     * 同 requestId 重放裁决：jointBatchKey 相同且测量集合相同（换序视为同参）返回首次响应快照；异参 409。
     */
    private JointReleaseResponse replayOrConflict(JointReleaseBatch existing, String jointBatchKey,
                                                  Set<String> keySet) {
        List<String> snapshotKeys = readSnapshot(existing.snapshotKeys());
        if (existing.jointBatchKey().equals(jointBatchKey)
                && new LinkedHashSet<>(snapshotKeys).equals(keySet)) {
            // 返回首次成功响应的原始快照（含首次请求的返回顺序）
            return new JointReleaseResponse(existing.jointBatchKey(), existing.requestId(),
                    existing.releasedBy(), existing.releasedAt(), List.copyOf(snapshotKeys));
        }
        throw ApiException.conflict("IDEMPOTENCY_PARAM_CONFLICT",
                "requestId 已用于不同参数的联合批次放行: " + existing.requestId());
    }

    private String writeSnapshot(List<String> orderedKeys) {
        try {
            return JSON.writeValueAsString(orderedKeys);
        } catch (JsonProcessingException ex) {
            throw ApiException.badRequest("测量键序列化失败");
        }
    }

    private List<String> readSnapshot(String json) {
        try {
            return JSON.readerForListOf(String.class).readValue(json);
        } catch (JsonProcessingException ex) {
            throw ApiException.conflict("JOINT_BATCH_SNAPSHOT_CORRUPT", "联合批次响应快照损坏");
        }
    }
}
