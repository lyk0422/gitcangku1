package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CertificationSnapshotView;
import com.example.starter.maintenance.api.dto.CertifyBatchRequest;
import com.example.starter.maintenance.api.dto.CertifyBatchResponse;
import com.example.starter.maintenance.api.dto.CertifyBatchResponse.CertifiedReadingView;
import com.example.starter.maintenance.api.dto.CertifyBatchResponse.EquipmentCertResult;
import com.example.starter.maintenance.api.dto.CertifyReadingItem;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RetireRequest;
import com.example.starter.maintenance.api.dto.RetireResponse;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.CertificationSnapshot;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 * 认证：新增/修订的读数为 PENDING，不参与累计工时与保养判定；认证成功在同一事务内转 CERTIFIED、
 * 重算设备累计工时与保养到期状态并写入不可变认证快照；批次认证任一失败整批回滚。
 */
@Service
public class EquipmentTxService {

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public EquipmentTxService(EquipmentRepository repository, IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 登记设备 ----------

    @Transactional
    public EquipmentResponse register(RegisterEquipmentRequest req) {
        String fingerprint = req.equipmentId() + "|" + req.maintenancePeriodMinutes();
        return idempotency.execute(req.requestId(), "REGISTER_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    if (repository.findEquipment(req.equipmentId()).isPresent()) {
                        throw ApiException.conflict("EQUIPMENT_EXISTS", "设备已存在：" + req.equipmentId());
                    }
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), clock.instant());
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.cumulativeMinutes() + "|" + req.expectedVersion() + "|" + req.recordedBy();
        return idempotency.execute(req.requestId(), "ADD_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findReading(equipmentId, req.readingId()).isPresent()) {
                        throw ApiException.conflict("READING_EXISTS", "读数已存在：" + req.readingId());
                    }
                    if (repository.findReadingAt(equipmentId, req.sampledAt()).isPresent()) {
                        throw ApiException.unprocessable("READING_TIME_DUPLICATE",
                                "同一设备同一采样时刻仅允许一条读数");
                    }
                    Instant now = clock.instant();
                    repository.insertReading(
                            new Reading(equipmentId, req.readingId(), req.sampledAt(),
                                    req.cumulativeMinutes(), 1, Reading.STATUS_PENDING,
                                    req.recordedBy(), null, null),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.recordedBy(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, equipment.version() + 1,
                            Reading.STATUS_PENDING, req.recordedBy(), null, null);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion() + "|" + req.recordedBy();
        return idempotency.execute(req.requestId(), "REVISE_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    if (repository.existsMaintenanceAnchoringReading(equipmentId, readingId)) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已作为历史保养锚点，不可修订：" + readingId);
                    }
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, req.recordedBy(), now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.recordedBy(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, false, equipment.version() + 1,
                            Reading.STATUS_PENDING, req.recordedBy(), null, null);
                });
    }

    // ---------- 完成保养 ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.anchorRevisionNo()
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE", fingerprint,
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    if (!Reading.STATUS_CERTIFIED.equals(anchor.certStatus())) {
                        throw ApiException.unprocessable("READING_NOT_CERTIFIED",
                                "锚点读数未认证，不参与保养判定：" + req.readingId());
                    }
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于上次保养锚点时间");
                    }
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1);
                });
    }

    // ---------- 设备退役 ----------

    @Transactional
    public RetireResponse retire(String equipmentId, RetireRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "RETIRE_EQUIPMENT", fingerprint,
                RetireResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (equipment.retiredAt() != null) {
                        throw ApiException.conflict("EQUIPMENT_ALREADY_RETIRED",
                                "设备已退役：" + equipmentId);
                    }
                    Instant now = clock.instant();
                    repository.retireEquipment(equipmentId, now);
                    repository.incrementVersion(equipmentId);
                    return new RetireResponse(equipmentId, equipment.version() + 1, now);
                });
    }

    // ---------- 批次认证 ----------

    /** 规范化批次：按设备、读数标识排序，使指纹与请求内条目顺序无关。 */
    public static List<CertifyReadingItem> normalizeBatch(List<CertifyReadingItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(CertifyReadingItem::equipmentId)
                        .thenComparing(CertifyReadingItem::readingId))
                .toList();
    }

    /** certKey 指纹：认证人 + 规范化批次（设备、读数、读数版本）。 */
    public static String certifyFingerprint(String certifiedBy, List<CertifyReadingItem> normalized) {
        StringBuilder fingerprint = new StringBuilder(certifiedBy);
        for (CertifyReadingItem item : normalized) {
            fingerprint.append('|').append(item.equipmentId())
                    .append(':').append(item.readingId())
                    .append(':').append(item.revisionNo());
        }
        return fingerprint.toString();
    }

    @Transactional
    public CertifyBatchResponse certifyBatch(CertifyBatchRequest req) {
        List<CertifyReadingItem> normalized = normalizeBatch(req.items());
        // 先按设备标识升序逐台加行锁（避免死锁），再做幂等判定：与其他写操作保持
        // “设备行锁 → 幂等判定 → 业务规则”的同一顺序，保证并发同键重放一致
        Map<String, Equipment> locked = new TreeMap<>();
        for (CertifyReadingItem item : normalized) {
            locked.computeIfAbsent(item.equipmentId(), this::lockEquipment);
        }
        String fingerprint = certifyFingerprint(req.certifiedBy(), normalized);
        return idempotency.execute(req.certKey(), "CERTIFY_READINGS", fingerprint,
                CertifyBatchResponse.class,
                () -> doCertifyBatch(req.certKey(), req.certifiedBy(), normalized, locked));
    }

    private CertifyBatchResponse doCertifyBatch(String certKey, String certifiedBy,
                                                List<CertifyReadingItem> normalized,
                                                Map<String, Equipment> locked) {
        // 同一读数在批次内重复 → 422
        Map<String, CertifyReadingItem> seen = new LinkedHashMap<>();
        for (CertifyReadingItem item : normalized) {
            String key = item.equipmentId() + "/" + item.readingId();
            if (seen.putIfAbsent(key, item) != null) {
                throw ApiException.unprocessable("CERT_BATCH_DUPLICATE",
                        "批次内同一读数重复：" + key);
            }
        }

        // 按设备分组（设备标识升序）：录入/认证/修订/退役/保养按事务提交顺序裁决
        Map<String, List<CertifyReadingItem>> byEquipment = new TreeMap<>();
        for (CertifyReadingItem item : normalized) {
            byEquipment.computeIfAbsent(item.equipmentId(), k -> new ArrayList<>()).add(item);
        }

        Instant now = clock.instant();
        Map<String, List<Reading>> toCertify = new TreeMap<>();
        for (Map.Entry<String, List<CertifyReadingItem>> entry : byEquipment.entrySet()) {
            String equipmentId = entry.getKey();
            Equipment equipment = locked.get(equipmentId);
            if (equipment.retiredAt() != null) {
                throw ApiException.unprocessable("EQUIPMENT_RETIRED",
                        "设备已退役，不可认证读数：" + equipmentId);
            }
            List<Reading> readings = new ArrayList<>();
            for (CertifyReadingItem item : entry.getValue()) {
                Reading reading = repository.findReading(equipmentId, item.readingId())
                        .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                "读数不存在：" + item.readingId()));
                if (reading.recordedBy().equals(certifiedBy)) {
                    throw ApiException.forbidden("CERTIFIER_IS_RECORDER",
                            "录入人不得认证自己的读数：" + item.readingId());
                }
                if (reading.revisionNo() != item.revisionNo()) {
                    throw ApiException.unprocessable("READING_VERSION_CONFLICT",
                            "读数版本不一致：期望 " + item.revisionNo()
                                    + "，当前 " + reading.revisionNo() + "（" + item.readingId() + "）");
                }
                if (!Reading.STATUS_PENDING.equals(reading.certStatus())) {
                    throw ApiException.unprocessable("READING_ALREADY_CERTIFIED",
                            "读数已认证，不可重复认证：" + item.readingId());
                }
                readings.add(reading);
            }
            toCertify.put(equipmentId, readings);
        }

        // 最终序列单调性验证：已认证序列 ∪ 本批读数按采样时刻排序后累计工时须单调不减，任一倒退整批回滚
        for (Map.Entry<String, List<Reading>> entry : toCertify.entrySet()) {
            List<Reading> merged = new ArrayList<>(repository.listCertifiedReadings(entry.getKey()));
            merged.addAll(entry.getValue());
            merged.sort(Comparator.comparing(Reading::sampledAt));
            long previous = Long.MIN_VALUE;
            for (Reading reading : merged) {
                if (reading.cumulativeMinutes() < previous) {
                    throw ApiException.unprocessable("CERTIFICATION_ORDER_VIOLATION",
                            "认证后已认证序列出现倒退：" + reading.readingId()
                                    + "（" + reading.cumulativeMinutes() + " < " + previous + "），整批回滚");
                }
                previous = reading.cumulativeMinutes();
            }
        }

        // 应用：转 CERTIFIED、版本加一、重算累计工时与保养判定、写不可变快照
        List<EquipmentCertResult> results = new ArrayList<>();
        int batchSeq = 0;
        for (Map.Entry<String, List<Reading>> entry : toCertify.entrySet()) {
            String equipmentId = entry.getKey();
            List<Reading> readings = new ArrayList<>(entry.getValue());
            readings.sort(Comparator.comparing(Reading::sampledAt));
            for (Reading reading : readings) {
                repository.markReadingCertified(equipmentId, reading.readingId(), certifiedBy, now);
            }
            repository.incrementVersion(equipmentId);

            Equipment equipment = locked.get(equipmentId);
            long latestCumulative = repository.findLatestCertifiedReading(equipmentId)
                    .map(Reading::cumulativeMinutes).orElse(0L);
            long anchorCumulative = repository.findLastMaintenance(equipmentId)
                    .map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
            long runMinutes = latestCumulative - anchorCumulative;
            String dueStatus = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
            long newVersion = equipment.version() + 1;

            List<CertifiedReadingView> views = new ArrayList<>();
            for (Reading reading : readings) {
                batchSeq++;
                repository.insertCertificationSnapshot(new CertificationSnapshot(
                        0L, certKey, equipmentId, reading.readingId(), reading.revisionNo(),
                        reading.recordedBy(), certifiedBy, reading.cumulativeMinutes(), batchSeq,
                        latestCumulative, runMinutes, dueStatus, now));
                views.add(new CertifiedReadingView(reading.readingId(), reading.revisionNo(),
                        reading.cumulativeMinutes()));
            }
            results.add(new EquipmentCertResult(equipmentId, views, latestCumulative,
                    runMinutes, dueStatus, newVersion));
        }
        int certifiedCount = normalized.size();
        return new CertifyBatchResponse(certKey, certifiedBy, certifiedCount, now, results);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Optional<Reading> latest = repository.findLatestCertifiedReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), equipment.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status, equipment.retiredAt() != null, equipment.retiredAt());
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version(), reading.certStatus(), reading.recordedBy(),
                        reading.certifiedBy(), reading.certifiedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.requestId(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listMaintenances(equipmentId).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CertificationSnapshotView> listCertifications(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listCertificationSnapshots(equipmentId).stream()
                .map(snapshot -> new CertificationSnapshotView(snapshot.snapshotId(),
                        snapshot.certKey(), snapshot.equipmentId(), snapshot.readingId(),
                        snapshot.revisionNo(), snapshot.recordedBy(), snapshot.certifiedBy(),
                        snapshot.cumulativeMinutes(), snapshot.batchSeq(),
                        snapshot.latestCumulativeMinutes(), snapshot.runMinutes(),
                        snapshot.dueStatus(), snapshot.certifiedAt()))
                .toList();
    }

    // ---------- 内部规则 ----------

    private Equipment lockEquipment(String equipmentId) {
        return repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }

    private void checkVersion(Equipment equipment, long expectedVersion) {
        if (equipment.version() != expectedVersion) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + expectedVersion + "，当前 " + equipment.version());
        }
    }
}
