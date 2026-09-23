package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.DriftActivateResponse;
import com.example.starter.maintenance.api.dto.DriftAnchorRequest;
import com.example.starter.maintenance.api.dto.DriftAnchorView;
import com.example.starter.maintenance.api.dto.DriftCorrectionRequest;
import com.example.starter.maintenance.api.dto.DriftEvidenceResponse;
import com.example.starter.maintenance.api.dto.DriftMaintenanceItemView;
import com.example.starter.maintenance.api.dto.DriftPreviewResponse;
import com.example.starter.maintenance.api.dto.DriftReadingView;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.service.DriftCalculator.Plan;
import com.example.starter.maintenance.service.DriftCalculator.PlanAnchor;
import com.example.starter.maintenance.service.DriftCalculator.PlannedReading;
import com.example.starter.maintenance.store.EquipmentRepository;
import com.example.starter.maintenance.store.EquipmentRepository.DriftAnchorRow;
import com.example.starter.maintenance.store.EquipmentRepository.DriftCorrectionRow;
import com.example.starter.maintenance.store.EquipmentRepository.DriftReadingRow;

/**
 * 时钟漂移修正事务服务。
 * 预览只读；激活在一个事务内：设备行锁 → 幂等判定 → 版本校验 → 重读锚点与区间完整读数
 * → 拒绝遗漏/版本变化/插值倒退/冻结点/区间外相邻冲突 → 读数新版本与保养快照原子提交。
 */
@Service
public class DriftCorrectionTxService {

    private static final String PERIODIC_ITEM_KEY = "PERIODIC";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String CHANGE_TYPE_DRIFT = "DRIFT_CORRECTION";

    private final EquipmentRepository repository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DriftCorrectionTxService(EquipmentRepository repository, IdempotencyService idempotency,
                                    Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    // ---------- 预览（只读，不写数据） ----------

    @Transactional(readOnly = true)
    public DriftPreviewResponse preview(String equipmentId, DriftCorrectionRequest req) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        LoadedData data = load(equipmentId, req.anchors());
        Plan plan = DriftCalculator.compute(req.anchors(), data.readingsById, data.intervalReadings,
                data.frozenReadingIds);
        String violation = plan.violation();
        if (violation == null) {
            violation = DriftCalculator.boundaryViolation(plan, data.leftNeighbor, data.rightNeighbor);
        }
        if (violation == null && equipment.version() != req.expectedVersion()) {
            violation = "VERSION_CONFLICT";
        }
        List<DriftReadingView> readingViews = toReadingViews(plan.readings());
        BigDecimal projectedLatestHours = projectedLatestHours(plan, data);
        List<DriftMaintenanceItemView> itemViews = recomputeItems(equipment, projectedLatestHours);
        return new DriftPreviewResponse(req.correctionKey(), equipmentId,
                plan.anchors().isEmpty() ? null : plan.intervalStart(),
                plan.anchors().isEmpty() ? null : plan.intervalEnd(),
                toAnchorViews(plan.anchors()), readingViews, itemViews,
                violation == null, violation);
    }

    // ---------- 激活（原子写） ----------

    @Transactional
    public DriftActivateResponse activate(String equipmentId, DriftCorrectionRequest req) {
        Equipment equipment = repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        String fingerprint = fingerprint(req);
        return idempotency.execute(req.requestId(), "DRIFT_CORRECTION", fingerprint,
                DriftActivateResponse.class, () -> doActivate(equipment, req));    }

    private DriftActivateResponse doActivate(Equipment equipment, DriftCorrectionRequest req) {
        String equipmentId = equipment.equipmentId();
        if (equipment.version() != req.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "设备版本冲突：期望 " + req.expectedVersion() + "，当前 " + equipment.version());
        }
        if (repository.findDriftCorrection(req.correctionKey()).isPresent()) {
            throw ApiException.conflict("CORRECTION_KEY_EXISTS",
                    "修正单已存在：" + req.correctionKey());
        }

        // 事务内重读锚点及区间完整读数：拒绝遗漏、版本变化。
        LoadedData data = load(equipmentId, req.anchors());
        Plan plan = DriftCalculator.compute(req.anchors(), data.readingsById, data.intervalReadings,
                data.frozenReadingIds);
        if (!plan.valid()) {
            if ("ANCHOR_REVISION_CONFLICT".equals(plan.violation())) {
                throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                        "锚点读数版本已变化，请重读后重试");
            }
            throw rejection(plan.violation());
        }
        String boundary = DriftCalculator.boundaryViolation(plan, data.leftNeighbor, data.rightNeighbor);
        if (boundary != null) {
            throw rejection(boundary);
        }

        Instant now = clock.instant();
        long snapshotVersion = equipment.maintenanceSnapshotVersion() + 1;

        // 1) 每条受影响读数生成新版本，旧版本不可变。
        for (PlannedReading planned : plan.readings()) {
            Reading reading = planned.reading();
            BigDecimal newHours = planned.newHours();
            long newMinutes = newHours.multiply(Reading.HOURS_PER_SIXTY)
                    .setScale(0, RoundingMode.HALF_UP).longValue();
            repository.updateReadingCorrected(equipmentId, reading.readingId(), newHours, newMinutes,
                    planned.newRevisionNo(), now);
            repository.insertRevision(equipmentId, reading.readingId(), planned.newRevisionNo(),
                    newHours, CHANGE_TYPE_DRIFT, req.requestId(), now);
        }

        // 2) 按修正后的当前累计工时一次性重算全部保养项目，只生成一个 maintenanceSnapshotVersion。
        LoadedData refreshed = reloadAfterCorrection(equipmentId, plan);
        BigDecimal latestHours = refreshed.latest == null ? BigDecimal.ZERO
                : refreshed.latest.cumulativeHours().setScale(DriftCalculator.LEGACY_SCALE,
                        RoundingMode.HALF_UP);
        List<DriftMaintenanceItemView> items = recomputeItems(equipment, latestHours);
        Reading latest = refreshed.latest;
        long snapshotId = repository.insertMaintenanceSnapshot(equipmentId, snapshotVersion,
                req.correctionKey(), latest == null ? null : latest.readingId(),
                latest == null ? null : latest.sampledAt(), latestHours, now);
        for (DriftMaintenanceItemView item : items) {
            repository.insertMaintenanceItemSnapshot(snapshotId, item.itemKey(), item.status(),
                    item.runHours(), item.nextThresholdHours(), item.lastAnchorReadingId(),
                    item.lastAnchorCumulativeHours());
        }

        // 3) 修正单及证据落库（correctionKey 唯一）。
        repository.insertDriftCorrection(new DriftCorrectionRow(
                req.correctionKey(), equipmentId, req.expectedVersion(),
                equipment.version() + 1, snapshotVersion,
                plan.intervalStart(), plan.intervalEnd(), req.requestId(), STATUS_ACTIVE, now));
        List<PlanAnchor> anchors = plan.anchors();
        for (int i = 0; i < anchors.size(); i++) {
            PlanAnchor anchor = anchors.get(i);
            repository.insertDriftAnchor(new DriftAnchorRow(
                    req.correctionKey(), i + 1, anchor.reading().readingId(),
                    anchor.reading().sampledAt(), anchor.expectedRevisionNo(),
                    anchor.calibratedHours()));
        }
        for (PlannedReading planned : plan.readings()) {
            repository.insertDriftReading(new DriftReadingRow(
                    req.correctionKey(), planned.reading().readingId(), planned.reading().sampledAt(),
                    planned.anchor(), planned.segmentIndex(), planned.frozen(),
                    planned.oldRevisionNo(), planned.newRevisionNo(),
                    planned.oldHours(), planned.newHours()));
        }

        // 4) 设备版本与快照版本原子加一；与读数修正、保养重算同事务提交。
        repository.incrementVersionAndSnapshot(equipmentId);

        return new DriftActivateResponse(req.correctionKey(), equipmentId, equipment.version() + 1,
                snapshotVersion, plan.intervalStart(), plan.intervalEnd(),
                toReadingViews(plan.readings()), items, now);
    }

    // ---------- 证据查询（只读、稳定排序） ----------

    @Transactional(readOnly = true)
    public DriftEvidenceResponse evidence(String equipmentId, String correctionKey) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        DriftCorrectionRow correction = repository.findDriftCorrection(correctionKey)
                .orElseThrow(() -> ApiException.notFound("CORRECTION_NOT_FOUND",
                        "修正单不存在：" + correctionKey));
        if (!correction.equipmentId().equals(equipmentId)) {
            throw ApiException.notFound("CORRECTION_NOT_FOUND",
                    "修正单不属于该设备：" + correctionKey);
        }
        List<DriftAnchorView> anchors = repository.listDriftAnchors(correctionKey).stream()
                .map(row -> new DriftAnchorView(row.positionNo(), row.readingId(), row.sampledAt(),
                        row.expectedRevisionNo(), row.calibratedHours()))
                .toList();
        List<DriftReadingView> readings = repository.listDriftReadings(correctionKey).stream()
                .map(row -> new DriftReadingView(row.readingId(), row.sampledAt(), row.anchor(),
                        row.frozen(), row.segmentIndex(), row.oldRevisionNo(), row.newRevisionNo(),
                        row.oldHours(), row.newHours()))
                .toList();
        List<DriftMaintenanceItemView> items = repository
                .listMaintenanceItemsOfCorrection(correctionKey).stream()
                .map(row -> new DriftMaintenanceItemView(row.itemKey(), row.status(),
                        row.runHours(), row.nextThresholdHours(), row.lastAnchorReadingId(),
                        row.lastAnchorCumulativeHours()))
                .toList();
        return new DriftEvidenceResponse(correctionKey, equipmentId,
                correction.equipmentVersionAfter(), correction.maintenanceSnapshotVersion(),
                correction.intervalStart(), correction.intervalEnd(), correction.activatedAt(),
                anchors, readings, items);
    }

    // ---------- 内部 ----------

    private record LoadedData(
            Map<String, Reading> readingsById,
            List<Reading> intervalReadings,
            Set<String> frozenReadingIds,
            Reading leftNeighbor,
            Reading rightNeighbor,
            Reading latest) {
    }

    /** 重读设备全部读数、区间完整读数、冻结点与区间外相邻读数；锚点遗漏直接 422。 */
    private LoadedData load(String equipmentId, List<DriftAnchorRequest> requested) {
        List<Reading> all = repository.listReadings(equipmentId);
        Map<String, Reading> readingsById = new LinkedHashMap<>();
        for (Reading reading : all) {
            readingsById.put(reading.readingId(), reading);
        }
        for (DriftAnchorRequest anchor : requested) {
            if (!readingsById.containsKey(anchor.readingId())) {
                throw ApiException.unprocessable("CORRECTION_ANCHOR_READING_NOT_FOUND",
                        "锚点读数不存在：" + anchor.readingId());
            }
        }
        List<Reading> sortedAnchorReadings = requested.stream()
                .sorted(java.util.Comparator.comparing(a -> readingsById.get(a.readingId()).sampledAt()))
                .map(a -> readingsById.get(a.readingId()))
                .toList();
        Instant start = sortedAnchorReadings.get(0).sampledAt();
        Instant end = sortedAnchorReadings.get(sortedAnchorReadings.size() - 1).sampledAt();
        List<Reading> interval = all.stream()
                .filter(r -> !r.sampledAt().isBefore(start) && !r.sampledAt().isAfter(end))
                .toList();
        Reading leftNeighbor = all.stream()
                .filter(r -> r.sampledAt().isBefore(start))
                .reduce((first, second) -> second).orElse(null);
        Reading rightNeighbor = all.stream()
                .filter(r -> r.sampledAt().isAfter(end))
                .findFirst().orElse(null);
        Reading latest = all.isEmpty() ? null : all.get(all.size() - 1);
        Set<String> frozen = repository.listMaintenances(equipmentId).stream()
                .map(MaintenanceRecord::readingId)
                .collect(Collectors.toSet());
        return new LoadedData(readingsById, interval, frozen, leftNeighbor, rightNeighbor, latest);
    }

    /** 激活写入修正值后重新读取全量读数，保证保养重算基于修正后的当前累计工时。 */
    private LoadedData reloadAfterCorrection(String equipmentId, Plan plan) {
        List<Reading> all = repository.listReadings(equipmentId);
        Map<String, Reading> readingsById = new LinkedHashMap<>();
        for (Reading reading : all) {
            readingsById.put(reading.readingId(), reading);
        }
        List<Reading> interval = repository.listReadingsBetween(equipmentId,
                plan.intervalStart(), plan.intervalEnd());
        Reading latest = all.isEmpty() ? null : all.get(all.size() - 1);
        return new LoadedData(readingsById, interval, Set.of(), null, null, latest);
    }

    /**
     * 预览时投影修正后的最新工时：最新读数落在区间内则取其修正新值，否则取当前值。
     */
    private BigDecimal projectedLatestHours(Plan plan, LoadedData data) {
        if (data.latest == null) {
            return BigDecimal.ZERO.setScale(DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP);
        }
        return plan.readings().stream()
                .filter(p -> p.reading().readingId().equals(data.latest.readingId()))
                .findFirst()
                .map(p -> p.newHours().setScale(DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP))
                .orElseGet(() -> data.latest.cumulativeHours()
                        .setScale(DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * 按修正后的当前累计工时重算全部保养项目。当前设备含一个周期保养项目（PERIODIC）：
     * runHours = 最新读数工时 - 最近保养锚点工时；达到工时周期即 DUE，下一阈值为锚点加工时周期。
     */
    private List<DriftMaintenanceItemView> recomputeItems(Equipment equipment,
                                                          BigDecimal latestHoursArg) {
        BigDecimal periodHours = BigDecimal.valueOf(equipment.maintenancePeriodMinutes())
                .divide(Reading.HOURS_PER_SIXTY, DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP);
        Optional<MaintenanceRecord> lastMaint =
                repository.findLastMaintenance(equipment.equipmentId());
        String anchorReadingId = null;
        BigDecimal anchorHours = BigDecimal.ZERO.setScale(DriftCalculator.LEGACY_SCALE,
                RoundingMode.HALF_UP);
        if (lastMaint.isPresent()) {
            MaintenanceRecord record = lastMaint.get();
            anchorReadingId = record.readingId();
            // 锚点快照不变：保养完成时冻结的累计工时（分钟快照精确换算）。
            anchorHours = BigDecimal.valueOf(record.anchorCumulativeMinutes())
                    .divide(Reading.HOURS_PER_SIXTY, DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal latestHours = latestHoursArg.setScale(DriftCalculator.LEGACY_SCALE,
                RoundingMode.HALF_UP);
        BigDecimal runHours = latestHours.subtract(anchorHours).max(BigDecimal.ZERO)
                .setScale(DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP);
        String status = runHours.compareTo(periodHours) >= 0 ? "DUE" : "NOT_DUE";
        BigDecimal nextThreshold = anchorHours.add(periodHours)
                .setScale(DriftCalculator.LEGACY_SCALE, RoundingMode.HALF_UP);
        return List.of(new DriftMaintenanceItemView(PERIODIC_ITEM_KEY, status, runHours,
                nextThreshold, anchorReadingId, anchorHours));
    }

    private List<DriftAnchorView> toAnchorViews(List<PlanAnchor> anchors) {
        List<DriftAnchorView> views = new java.util.ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            PlanAnchor anchor = anchors.get(i);
            views.add(new DriftAnchorView(i + 1, anchor.reading().readingId(),
                    anchor.reading().sampledAt(), anchor.expectedRevisionNo(),
                    anchor.calibratedHours()));
        }
        return views;
    }

    private List<DriftReadingView> toReadingViews(List<PlannedReading> planned) {
        return planned.stream()
                .map(p -> new DriftReadingView(p.reading().readingId(), p.reading().sampledAt(),
                        p.anchor(), p.frozen(), p.segmentIndex(), p.oldRevisionNo(), p.newRevisionNo(),
                        p.oldHours(), p.newHours()))
                .toList();
    }

    /** 幂等指纹：锚点按读数标识（与采样时刻同序）规范化后参与指纹，锚点换序提交等价。 */
    public static String fingerprint(DriftCorrectionRequest req) {
        String anchors = req.anchors().stream()
                .sorted(java.util.Comparator.comparing(DriftAnchorRequest::readingId))
                .map(a -> a.readingId() + ":" + a.expectedVersion() + ":"
                        + a.calibratedHours().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
        return req.correctionKey() + "|" + req.expectedVersion() + "|" + anchors;
    }

    private ApiException rejection(String code) {
        String message = switch (code) {
            case "CORRECTION_ANCHOR_DUPLICATED" -> "同一读数在锚点中重复出现";
            case "CORRECTION_ANCHOR_READING_NOT_FOUND" -> "锚点读数不存在或区间内存在遗漏";
            case "CORRECTION_ANCHOR_TIME_NOT_ORDERED" -> "锚点采样时刻必须互不相同";
            case "CORRECTION_ANCHOR_NOT_STRICT_INCREASING" ->
                    "相邻锚点校准工时必须严格递增";
            case "CORRECTION_ANCHOR_PRECISION" ->
                    "校准工时精确到 0.001 小时，不允许超过三位小数";
            case "ANCHOR_REVISION_CONFLICT" -> "锚点读数版本已变化，请重读后重试";
            case "CORRECTION_FROZEN_READING" ->
                    "区间内存在被已完成保养记录冻结的读数，整单不可修正";
            case "CORRECTION_INTERPOLATED_REGRESSION" ->
                    "插值修正后累计工时序列出现倒退，违反单调不减约束";
            case "CORRECTION_BOUNDARY_CONFLICT" ->
                    "修正值与区间外相邻读数冲突，违反单调不减约束";
            default -> "漂移修正被拒绝：" + code;
        };
        return ApiException.unprocessable(code, message);
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }
}
