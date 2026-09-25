package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CertificationSnapshotView;
import com.example.starter.maintenance.api.dto.CertifyReadingsRequest;
import com.example.starter.maintenance.api.dto.CertifyReadingsResponse;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.RetireEquipmentRequest;
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
 * 读数认证：新读数与修订版本均为 PENDING，须由不同于录入人的认证人认证（双人认证）后才
 * 参与累计工时与保养阈值判定；认证成功在同一事务内转 CERTIFIED、重算并写入不可变快照。
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
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L, false);
                });
    }

    // ---------- 设备退役 ----------

    @Transactional
    public EquipmentResponse retire(String equipmentId, RetireEquipmentRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "RETIRE_EQUIPMENT", fingerprint,
                EquipmentResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (equipment.retired()) {
                        throw ApiException.conflict("EQUIPMENT_ALREADY_RETIRED",
                                "设备已退役：" + equipmentId);
                    }
                    repository.retireEquipment(equipmentId, clock.instant());
                    repository.incrementVersion(equipmentId);
                    return new EquipmentResponse(equipmentId, equipment.maintenancePeriodMinutes(),
                            equipment.version() + 1, true);
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
                    checkMonotonic(equipmentId, req.sampledAt(), req.cumulativeMinutes());
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
                            req.cumulativeMinutes(), 1, Reading.STATUS_PENDING, req.recordedBy(),
                            null, false, equipment.version() + 1);
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
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, req.recordedBy(), now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.recordedBy(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, Reading.STATUS_PENDING,
                            req.recordedBy(), null, false, equipment.version() + 1);
                });
    }

    // ---------- 认证读数（批量，可跨设备） ----------

    @Transactional
    public CertifyReadingsResponse certify(CertifyReadingsRequest req) {
        // 规范化批次：按设备与读数标识排序构造指纹，同集合不同顺序视为同一请求
        List<CertifyReadingsRequest.Item> normalized = req.items().stream()
                .sorted(Comparator.comparing(CertifyReadingsRequest.Item::equipmentId)
                        .thenComparing(CertifyReadingsRequest.Item::readingId))
                .toList();
        for (int i = 1; i < normalized.size(); i++) {
            CertifyReadingsRequest.Item prev = normalized.get(i - 1);
            CertifyReadingsRequest.Item curr = normalized.get(i);
            if (prev.equipmentId().equals(curr.equipmentId())
                    && prev.readingId().equals(curr.readingId())) {
                throw ApiException.unprocessable("BATCH_DUPLICATE_READING",
                        "批次内读数重复：" + curr.equipmentId() + "/" + curr.readingId());
            }
        }
        // 先按设备标识排序锁定全部涉及设备（避免死锁），再做幂等判定，与其他写操作一致
        Map<String, Equipment> equipments = new LinkedHashMap<>();
        normalized.stream()
                .map(CertifyReadingsRequest.Item::equipmentId)
                .distinct()
                .sorted()
                .forEach(equipmentId -> equipments.put(equipmentId, lockEquipment(equipmentId)));
        // certKey 指纹：认证人 + 规范化批次（设备/读数/读数版本）
        StringBuilder fingerprint = new StringBuilder(req.certifier());
        for (CertifyReadingsRequest.Item item : normalized) {
            fingerprint.append('|').append(item.equipmentId()).append(':')
                    .append(item.readingId()).append(':').append(item.expectedRevisionNo());
        }
        return idempotency.execute(req.requestId(), "CERTIFY_READINGS", fingerprint.toString(),
                CertifyReadingsResponse.class, () -> doCertify(req, normalized, equipments));
    }

    /**
     * 认证主流程：逐条校验（设备未退役/读数版本/双人认证）→ 按设备与采样时刻排序验证最终
     * 已认证序列单调不减 → 同事务转 CERTIFIED、重算、写快照。
     * 任一校验失败抛异常整批回滚，不改变累计工时与已触发保养状态，也不占 certKey。
     */
    private CertifyReadingsResponse doCertify(CertifyReadingsRequest req,
                                              List<CertifyReadingsRequest.Item> normalized,
                                              Map<String, Equipment> equipments) {
        // 1. 退役设备不可认证
        for (Equipment equipment : equipments.values()) {
            if (equipment.retired()) {
                throw ApiException.unprocessable("EQUIPMENT_RETIRED",
                        "设备已退役，读数不可认证：" + equipment.equipmentId());
            }
        }

        // 2. 逐条加载并校验读数
        List<Reading> pending = new ArrayList<>();
        for (CertifyReadingsRequest.Item item : normalized) {
            Reading reading = repository.findReading(item.equipmentId(), item.readingId())
                    .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                            "读数不存在：" + item.equipmentId() + "/" + item.readingId()));
            if (!Reading.STATUS_PENDING.equals(reading.status())) {
                throw ApiException.conflict("READING_ALREADY_CERTIFIED",
                        "读数当前修订版本已认证：" + item.readingId());
            }
            if (reading.revisionNo() != item.expectedRevisionNo()) {
                throw ApiException.unprocessable("READING_VERSION_CONFLICT",
                        "读数版本冲突：期望认证修订号 " + item.expectedRevisionNo()
                                + "，当前 " + reading.revisionNo());
            }
            if (reading.recordedBy().equals(req.certifier())) {
                throw ApiException.forbidden("CERTIFIER_IS_RECORDER",
                        "认证人不得认证自己录入的读数：" + item.readingId());
            }
            pending.add(reading);
        }

        // 3. 按设备与采样时刻排序，验证认证后的最终已认证序列单调不减
        pending.sort(Comparator.comparing(Reading::equipmentId)
                .thenComparing(Reading::sampledAt));
        Map<String, Long> batchLastValue = new LinkedHashMap<>();
        for (Reading reading : pending) {
            String equipmentId = reading.equipmentId();
            long value = reading.cumulativeMinutes();
            Long batchPrev = batchLastValue.get(equipmentId);
            if (batchPrev != null && value < batchPrev) {
                throw certificationRegression(reading, batchPrev);
            }
            Optional<Reading> dbPrev = repository.findPrevCertifiedReading(equipmentId,
                    reading.sampledAt());
            if (dbPrev.isPresent() && value < dbPrev.get().cumulativeMinutes()) {
                throw certificationRegression(reading, dbPrev.get().cumulativeMinutes());
            }
            Optional<Reading> dbNext = repository.findNextCertifiedReading(equipmentId,
                    reading.sampledAt());
            if (dbNext.isPresent() && value > dbNext.get().cumulativeMinutes()) {
                throw ApiException.unprocessable("CERTIFICATION_SEQUENCE_REGRESSION",
                        "认证后已认证序列出现倒退：" + value + " 大于后一条已认证读数（"
                                + dbNext.get().cumulativeMinutes() + "）");
            }
            batchLastValue.put(equipmentId, value);
        }

        // 4. 同事务生效：转 CERTIFIED、设备版本加一、重算累计工时与保养判定、写不可变快照
        Instant now = clock.instant();
        for (Reading reading : pending) {
            repository.certifyReading(reading.equipmentId(), reading.readingId(),
                    req.certifier(), now);
        }
        Map<String, CertifyReadingsResponse.EquipmentRecompute> recomputes = new LinkedHashMap<>();
        for (Map.Entry<String, Equipment> entry : equipments.entrySet()) {
            String equipmentId = entry.getKey();
            repository.incrementVersion(equipmentId);
            recomputes.put(equipmentId, recompute(entry.getValue()));
        }
        List<CertifyReadingsResponse.CertifiedReading> certifiedReadings = new ArrayList<>();
        for (Reading reading : pending) {
            CertifyReadingsResponse.EquipmentRecompute recompute = recomputes.get(reading.equipmentId());
            long certificationId = repository.insertCertification(req.requestId(),
                    reading.equipmentId(), reading.readingId(), reading.revisionNo(),
                    req.certifier(), reading.cumulativeMinutes(),
                    recompute.latestCertifiedCumulativeMinutes(), recompute.runMinutes(),
                    recompute.maintenanceStatus(), recompute.version(), now);
            certifiedReadings.add(new CertifyReadingsResponse.CertifiedReading(certificationId,
                    reading.equipmentId(), reading.readingId(), reading.revisionNo(),
                    reading.cumulativeMinutes()));
        }
        return new CertifyReadingsResponse(req.requestId(), req.certifier(), now,
                certifiedReadings, List.copyOf(recomputes.values()));
    }

    private ApiException certificationRegression(Reading reading, long previousValue) {
        return ApiException.unprocessable("CERTIFICATION_SEQUENCE_REGRESSION",
                "认证读数 " + reading.readingId() + " 的累计工时（" + reading.cumulativeMinutes()
                        + "）小于上一条已认证读数（" + previousValue + "），整批回滚");
    }

    /** 重算设备累计工时与保养判定：仅已认证读数参与。 */
    private CertifyReadingsResponse.EquipmentRecompute recompute(Equipment equipment) {
        Optional<Reading> latest = repository.findLatestCertifiedReading(equipment.equipmentId());
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipment.equipmentId());
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= equipment.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new CertifyReadingsResponse.EquipmentRecompute(equipment.equipmentId(),
                equipment.version() + 1, latestCumulative, runMinutes, status);
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
                    if (!Reading.STATUS_CERTIFIED.equals(anchor.status())) {
                        throw ApiException.unprocessable("ANCHOR_NOT_CERTIFIED",
                                "锚点读数未认证，不能作为保养锚点：" + req.readingId());
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
                runMinutes, status);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(), reading.status(),
                        reading.recordedBy(), reading.certifiedBy(),
                        repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId()),
                        equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RevisionView> listRevisions(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        return repository.listRevisions(equipmentId, readingId).stream()
                .map(row -> new RevisionView(row.revisionNo(), row.cumulativeMinutes(),
                        row.recordedBy(), row.requestId(), row.createdAt()))
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

    /** 指定读数最近一次认证快照。 */
    @Transactional(readOnly = true)
    public CertificationSnapshotView getCertification(String equipmentId, String readingId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findReading(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在：" + readingId));
        CertificationSnapshot snapshot = repository.findLatestCertification(equipmentId, readingId)
                .orElseThrow(() -> ApiException.notFound("CERTIFICATION_NOT_FOUND",
                        "读数尚未认证：" + readingId));
        return toView(snapshot);
    }

    /** 设备全部认证快照（按认证顺序升序）。 */
    @Transactional(readOnly = true)
    public List<CertificationSnapshotView> listCertifications(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listCertifications(equipmentId).stream()
                .map(this::toView)
                .toList();
    }

    private CertificationSnapshotView toView(CertificationSnapshot snapshot) {
        return new CertificationSnapshotView(snapshot.certificationId(), snapshot.requestId(),
                snapshot.equipmentId(), snapshot.readingId(), snapshot.revisionNo(),
                snapshot.certifiedBy(), snapshot.cumulativeMinutes(),
                snapshot.latestCertifiedCumulativeMinutes(), snapshot.runMinutes(),
                snapshot.maintenanceStatus(), snapshot.equipmentVersion(), snapshot.certifiedAt());
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

    /** 单调性校验：新值须同时不早于前相邻读数、不晚于后相邻读数（按采样时刻排序）。 */
    private void checkMonotonic(String equipmentId, Instant sampledAt, long cumulativeMinutes) {
        Optional<Reading> prev = repository.findPrevReading(equipmentId, sampledAt);
        if (prev.isPresent() && cumulativeMinutes < prev.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时小于前一条读数（" + prev.get().cumulativeMinutes() + "），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(equipmentId, sampledAt);
        if (next.isPresent() && cumulativeMinutes > next.get().cumulativeMinutes()) {
            throw ApiException.unprocessable("READING_ORDER_VIOLATION",
                    "累计工时大于后一条读数（" + next.get().cumulativeMinutes() + "），违反单调不减约束");
        }
    }
}
