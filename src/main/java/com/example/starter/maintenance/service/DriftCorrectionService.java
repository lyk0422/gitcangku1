package com.example.starter.maintenance.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.DriftAnchorInput;
import com.example.starter.maintenance.api.dto.DriftAnchorView;
import com.example.starter.maintenance.api.dto.DriftCorrectionActivateRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionDetailView;
import com.example.starter.maintenance.api.dto.DriftCorrectionItemView;
import com.example.starter.maintenance.api.dto.DriftCorrectionPreviewRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionPreviewResponse;
import com.example.starter.maintenance.api.dto.DriftCorrectionResponse;
import com.example.starter.maintenance.api.dto.DriftCorrectionSummaryView;
import com.example.starter.maintenance.api.dto.MaintenanceItemView;
import com.example.starter.maintenance.api.dto.MaintenanceSnapshotView;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.MeterUnits;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.DriftCorrectionRepository;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 时钟漂移修正事务业务服务。
 * 预览：只计算不写数据。激活：设备行锁 → 锚点按采样时刻规范化 → 幂等判定 →
 * 版本/冻结/单调校验 → 区间内每条读数生成新修订（旧版本不可变）→
 * 同事务一次性重算保养项目并恰好生成一个 maintenanceSnapshotVersion。
 * 锚点换序按时间规范化后等价；requestId 同参重放首次快照，异参 409，失败不占键。
 */
@Service
public class DriftCorrectionService {

    /** 幂等操作类型。 */
    public static final String OPERATION = "DRIFT_CORRECTION";

    private static final String STATUS_DUE = "DUE";
    private static final String STATUS_NOT_DUE = "NOT_DUE";
    private static final String MAINTENANCE_ITEM_KEY = "MAIN";

    private final EquipmentRepository repository;
    private final DriftCorrectionRepository driftRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public DriftCorrectionService(EquipmentRepository repository,
                                  DriftCorrectionRepository driftRepository,
                                  IdempotencyService idempotency, Clock clock) {
        this.repository = repository;
        this.driftRepository = driftRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 幂等参数指纹：锚点按 readingId 字典序规范化（与按时间规范化等价：同一锚点集合
     * 任意提交顺序得到同一指纹），不含 requestId。
     */
    public static String fingerprint(String equipmentId, DriftCorrectionActivateRequest req) {
        String anchors = req.anchors().stream()
                .sorted(Comparator.comparing(DriftAnchorInput::readingId))
                .map(anchor -> anchor.readingId() + ":" + anchor.expectedVersion() + ":"
                        + anchor.cumulativeHours().movePointRight(3).toBigIntegerExact())
                .collect(Collectors.joining(","));
        return equipmentId + "|" + req.correctionKey() + "|" + req.expectedVersion() + "|" + anchors;
    }

    // ---------- 预览（不写数据） ----------

    @Transactional(readOnly = true)
    public DriftCorrectionPreviewResponse preview(String equipmentId, DriftCorrectionPreviewRequest req) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        List<AnchorPlan> anchors = loadAndNormalizeAnchors(equipmentId, req.anchors());
        CorrectionPlan plan = buildPlan(equipment, anchors);
        return new DriftCorrectionPreviewResponse(equipmentId, req.correctionKey(), anchors.size(),
                toAnchorViews(plan.anchors()), toItemViews(plan.items()),
                List.of(toMaintenanceItemView(plan.maintenanceItem())));
    }

    // ---------- 激活（事务：行锁 → 幂等 → 校验 → 修正 → 重算快照） ----------

    @Transactional
    public DriftCorrectionResponse activate(String equipmentId, DriftCorrectionActivateRequest req) {
        Equipment equipment = repository.findEquipmentForUpdate(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return idempotency.execute(req.requestId(), OPERATION, fingerprint(equipmentId, req),
                DriftCorrectionResponse.class, () -> {
                    if (equipment.version() != req.expectedVersion()) {
                        throw ApiException.conflict("VERSION_CONFLICT",
                                "设备版本冲突：期望 " + req.expectedVersion()
                                        + "，当前 " + equipment.version());
                    }
                    if (driftRepository.existsCorrection(req.correctionKey())) {
                        throw ApiException.conflict("CORRECTION_KEY_EXISTS",
                                "correctionKey 已被占用：" + req.correctionKey());
                    }
                    List<AnchorPlan> anchors = loadAndNormalizeAnchors(equipmentId, req.anchors());
                    CorrectionPlan plan = buildPlan(equipment, anchors);

                    Instant now = clock.instant();
                    for (ItemPlan item : plan.items()) {
                        long newMinutes = MeterUnits.millisToMinutesRounded(item.newMillis());
                        repository.updateReadingValue(equipmentId, item.readingId(), newMinutes,
                                item.newMillis(), item.newRevisionNo(), now);
                        repository.insertRevision(equipmentId, item.readingId(), item.newRevisionNo(),
                                newMinutes, item.newMillis(), req.requestId(), now);
                    }
                    repository.incrementVersion(equipmentId);

                    long snapshotVersion = driftRepository.maxSnapshotVersion(equipmentId) + 1;
                    MaintenanceItemPlan maintenance = plan.maintenanceItem();
                    driftRepository.insertSnapshot(new DriftCorrectionRepository.SnapshotRow(
                            equipmentId, snapshotVersion, req.correctionKey(),
                            maintenance.latestReadingId(), maintenance.latestSampledAt(),
                            maintenance.latestCumulativeMillisAfter(), maintenance.anchorCumulativeMillis(),
                            maintenance.runMillisAfter(), maintenance.periodMinutes(),
                            maintenance.statusAfter(),
                            maintenance.nextThresholdMillisAfter(), now));
                    driftRepository.insertCorrection(new DriftCorrectionRepository.CorrectionRow(
                            req.correctionKey(), equipmentId, req.requestId(), plan.anchors().size(),
                            plan.anchors().get(0).sampledAt(),
                            plan.anchors().get(plan.anchors().size() - 1).sampledAt(),
                            plan.items().size(), snapshotVersion, equipment.version() + 1, now));
                    for (AnchorPlan anchor : plan.anchors()) {
                        driftRepository.insertAnchor(req.correctionKey(),
                                new DriftCorrectionRepository.AnchorRow(anchor.seq(),
                                        anchor.readingId(), anchor.sampledAt(),
                                        anchor.expectedVersion(), anchor.calibratedMillis()));
                    }
                    for (ItemPlan item : plan.items()) {
                        driftRepository.insertItem(req.correctionKey(),
                                new DriftCorrectionRepository.ItemRow(item.seq(), item.readingId(),
                                        item.sampledAt(), item.segmentIndex(), item.oldMillis(),
                                        item.newMillis(), item.oldRevisionNo(),
                                        item.newRevisionNo()));
                    }
                    return new DriftCorrectionResponse(req.correctionKey(), equipmentId,
                            plan.anchors().size(), plan.items().size(), snapshotVersion,
                            equipment.version() + 1, toAnchorViews(plan.anchors()),
                            toItemViews(plan.items()),
                            List.of(toMaintenanceItemView(plan.maintenanceItem())), now);
                });
    }

    // ---------- 证据查询（只读，稳定排序） ----------

    @Transactional(readOnly = true)
    public List<DriftCorrectionSummaryView> listCorrections(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return driftRepository.listCorrections(equipmentId).stream()
                .map(row -> new DriftCorrectionSummaryView(row.correctionKey(), row.equipmentId(),
                        row.anchorCount(), row.firstSampledAt(), row.lastSampledAt(),
                        row.affectedCount(), row.maintenanceSnapshotVersion(),
                        row.equipmentVersion(), row.createdAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DriftCorrectionDetailView getCorrection(String equipmentId, String correctionKey) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        DriftCorrectionRepository.CorrectionRow row = driftRepository
                .findCorrection(equipmentId, correctionKey)
                .orElseThrow(() -> ApiException.notFound("CORRECTION_NOT_FOUND",
                        "漂移修正单不存在：" + correctionKey));
        List<DriftAnchorView> anchors = driftRepository.listAnchors(correctionKey).stream()
                .map(anchor -> new DriftAnchorView(anchor.seq(), anchor.readingId(),
                        anchor.sampledAt(), anchor.expectedRevisionNo(), anchor.calibratedMillis(),
                        hoursString(anchor.calibratedMillis())))
                .toList();
        List<DriftCorrectionItemView> items = driftRepository.listItems(correctionKey).stream()
                .map(item -> new DriftCorrectionItemView(item.seq(), item.readingId(),
                        item.sampledAt(), item.segmentIndex(),
                        MeterUnits.millisToMinutesRounded(item.oldMillis()), item.oldMillis(),
                        item.newMillis(), hoursString(item.newMillis()),
                        item.oldRevisionNo(), item.newRevisionNo()))
                .toList();
        return new DriftCorrectionDetailView(row.correctionKey(), row.equipmentId(),
                row.anchorCount(), row.firstSampledAt(), row.lastSampledAt(), row.affectedCount(),
                row.maintenanceSnapshotVersion(), row.equipmentVersion(), row.createdAt(),
                anchors, items);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceSnapshotView> listSnapshots(String equipmentId) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        return driftRepository.listSnapshots(equipmentId).stream()
                .map(row -> new MaintenanceSnapshotView(row.equipmentId(), row.snapshotVersion(),
                        row.correctionKey(), row.latestReadingId(), row.latestSampledAt(),
                        row.latestCumulativeMillis(), row.anchorCumulativeMillis(), row.runMillis(),
                        row.periodMinutes(), row.status(), row.nextThresholdMillis(),
                        row.createdAt()))
                .toList();
    }

    // ---------- 修正计划 ----------

    private record AnchorPlan(int seq, String readingId, Instant sampledAt,
                              int expectedVersion, long calibratedMillis) {
    }

    private record ItemPlan(int seq, String readingId, Instant sampledAt, int segmentIndex,
                            long oldMillis, long newMillis, int oldRevisionNo, int newRevisionNo) {
    }

    private record MaintenanceItemPlan(String itemKey, long periodMinutes,
                                       String latestReadingId, Instant latestSampledAt,
                                       long latestCumulativeMillisBefore,
                                       long latestCumulativeMillisAfter,
                                       long anchorCumulativeMillis,
                                       long runMillisBefore, String statusBefore,
                                       long nextThresholdMillisBefore,
                                       long runMillisAfter, String statusAfter,
                                       long nextThresholdMillisAfter) {
    }

    private record CorrectionPlan(List<AnchorPlan> anchors, List<ItemPlan> items,
                                  MaintenanceItemPlan maintenanceItem) {
    }

    /** 载入锚点读数并按采样时刻规范化排序：拒绝重复锚点（422）与遗漏读数（404）。 */
    private List<AnchorPlan> loadAndNormalizeAnchors(String equipmentId,
                                                     List<DriftAnchorInput> inputs) {
        Set<String> seen = new HashSet<>();
        for (DriftAnchorInput input : inputs) {
            if (!seen.add(input.readingId())) {
                throw ApiException.unprocessable("ANCHOR_DUPLICATE",
                        "锚点读数重复：" + input.readingId());
            }
        }
        List<AnchorPlan> anchors = new ArrayList<>();
        for (DriftAnchorInput input : inputs) {
            Reading reading = repository.findReading(equipmentId, input.readingId())
                    .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                            "锚点读数不存在：" + input.readingId()));
            long calibratedMillis = MeterUnits.milliHoursToMillis(
                    input.cumulativeHours().movePointRight(3).longValueExact());
            anchors.add(new AnchorPlan(0, input.readingId(), reading.sampledAt(),
                    input.expectedVersion(), calibratedMillis));
        }
        anchors.sort(Comparator.comparing(AnchorPlan::sampledAt));
        List<AnchorPlan> normalized = new ArrayList<>(anchors.size());
        for (int i = 0; i < anchors.size(); i++) {
            AnchorPlan anchor = anchors.get(i);
            normalized.add(new AnchorPlan(i + 1, anchor.readingId(), anchor.sampledAt(),
                    anchor.expectedVersion(), anchor.calibratedMillis()));
        }
        return normalized;
    }

    /** 构建修正计划：版本校验 → 锚点严格递增 → 区间读数 → 冻结点 → 插值 → 单调与边界 → 保养项目。 */
    private CorrectionPlan buildPlan(Equipment equipment, List<AnchorPlan> anchors) {
        String equipmentId = equipment.equipmentId();
        Map<String, Reading> anchorReadings = new HashMap<>();
        for (AnchorPlan anchor : anchors) {
            Reading reading = repository.findReading(equipmentId, anchor.readingId())
                    .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                            "锚点读数不存在：" + anchor.readingId()));
            if (reading.revisionNo() != anchor.expectedVersion()) {
                throw ApiException.conflict("ANCHOR_VERSION_CONFLICT",
                        "锚点读数 " + anchor.readingId() + " 修订号已变化：期望 "
                                + anchor.expectedVersion() + "，当前 " + reading.revisionNo());
            }
            anchorReadings.put(anchor.readingId(), reading);
        }
        for (int i = 1; i < anchors.size(); i++) {
            if (anchors.get(i).calibratedMillis() <= anchors.get(i - 1).calibratedMillis()) {
                throw ApiException.unprocessable("ANCHOR_VALUES_NOT_INCREASING",
                        "锚点校准值必须严格递增：第 " + i + " 个锚点（"
                                + anchors.get(i - 1).readingId() + "）不小于第 " + (i + 1)
                                + " 个锚点（" + anchors.get(i).readingId() + "）");
            }
        }

        Instant firstAt = anchors.get(0).sampledAt();
        Instant lastAt = anchors.get(anchors.size() - 1).sampledAt();
        List<Reading> inRange = repository.listReadingsInRange(equipmentId, firstAt, lastAt);
        for (Reading reading : inRange) {
            if (repository.existsMaintenanceAnchoringReading(equipmentId, reading.readingId())) {
                throw ApiException.unprocessable("CORRECTION_FROZEN_READING",
                        "读数 " + reading.readingId()
                                + " 已被已完成保养记录冻结为依据，落在修正集合内，整单不可修正");
            }
        }

        // 插值：毫小时（0.001 小时）域线性插值并四舍五入，再精确换算为毫秒
        long[] anchorTimes = anchors.stream().mapToLong(anchor -> nanos(anchor.sampledAt())).toArray();
        long[] anchorMilliHours = anchors.stream()
                .mapToLong(anchor -> MeterUnits.millisToMilliHoursExact(anchor.calibratedMillis()))
                .toArray();
        List<ItemPlan> items = new ArrayList<>(inRange.size());
        for (int i = 0; i < inRange.size(); i++) {
            Reading reading = inRange.get(i);
            long time = nanos(reading.sampledAt());
            int segment = segmentIndex(anchorTimes, time);
            long newMillis;
            AnchorPlan exact = findAnchorAt(anchors, reading.readingId());
            if (exact != null) {
                newMillis = exact.calibratedMillis();
            } else {
                newMillis = interpolateMillis(anchorTimes[segment - 1], anchorMilliHours[segment - 1],
                        anchorTimes[segment], anchorMilliHours[segment], time);
            }
            items.add(new ItemPlan(i + 1, reading.readingId(), reading.sampledAt(), segment,
                    reading.cumulativeMillis(), newMillis, reading.revisionNo(),
                    reading.revisionNo() + 1));
        }

        // 单调性：区间外相邻读数边界 + 区间内插值结果，整条序列须单调不减
        Optional<Reading> prev = repository.findPrevReading(equipmentId, firstAt);
        if (prev.isPresent() && items.get(0).newMillis() < prev.get().cumulativeMillis()) {
            throw ApiException.unprocessable("CORRECTION_BOUNDARY_CONFLICT",
                    "首锚点修正值 " + items.get(0).newMillis() + " 毫秒小于区间外前相邻读数 "
                            + prev.get().readingId() + "（" + prev.get().cumulativeMillis()
                            + " 毫秒），违反单调不减约束");
        }
        Optional<Reading> next = repository.findNextReading(equipmentId, lastAt);
        if (next.isPresent()
                && items.get(items.size() - 1).newMillis() > next.get().cumulativeMillis()) {
            throw ApiException.unprocessable("CORRECTION_BOUNDARY_CONFLICT",
                    "尾锚点修正值 " + items.get(items.size() - 1).newMillis()
                            + " 毫秒大于区间外后相邻读数 " + next.get().readingId() + "（"
                            + next.get().cumulativeMillis() + " 毫秒），违反单调不减约束");
        }
        for (int i = 1; i < items.size(); i++) {
            if (items.get(i).newMillis() < items.get(i - 1).newMillis()) {
                throw ApiException.unprocessable("CORRECTION_REGRESSION",
                        "插值后读数 " + items.get(i).readingId() + " 倒退："
                                + items.get(i).newMillis() + " 毫秒小于前一条 "
                                + items.get(i - 1).readingId() + "（"
                                + items.get(i - 1).newMillis() + " 毫秒）");
            }
        }

        return new CorrectionPlan(anchors, items, buildMaintenanceItem(equipment, items));
    }

    /** 保养项目重算：本轮运行时长 = 最新读数（修正后）- 最近保养锚点工时；达到周期即 DUE。 */
    private MaintenanceItemPlan buildMaintenanceItem(Equipment equipment, List<ItemPlan> items) {
        String equipmentId = equipment.equipmentId();
        Map<String, Long> corrected = items.stream()
                .collect(Collectors.toMap(ItemPlan::readingId, ItemPlan::newMillis));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId);
        long periodMillis = MeterUnits.minutesToMillis(equipment.maintenancePeriodMinutes());
        long anchorMillis = last.map(MaintenanceRecord::anchorCumulativeMillis).orElse(0L);
        long latestBefore = latest.map(Reading::cumulativeMillis).orElse(0L);
        long latestAfter = latest
                .map(reading -> corrected.getOrDefault(reading.readingId(),
                        reading.cumulativeMillis()))
                .orElse(0L);
        long runBefore = latestBefore - anchorMillis;
        long runAfter = latestAfter - anchorMillis;
        long nextThreshold = anchorMillis + periodMillis;
        return new MaintenanceItemPlan(MAINTENANCE_ITEM_KEY, equipment.maintenancePeriodMinutes(),
                latest.map(Reading::readingId).orElse(null),
                latest.map(Reading::sampledAt).orElse(null),
                latestBefore, latestAfter, anchorMillis,
                runBefore, statusOf(runBefore, periodMillis), nextThreshold,
                runAfter, statusOf(runAfter, periodMillis), nextThreshold);
    }

    // ---------- 内部规则 ----------

    private static String statusOf(long runMillis, long periodMillis) {
        return runMillis >= periodMillis ? STATUS_DUE : STATUS_NOT_DUE;
    }

    /** 读数时刻所属插值段：左锚点 seq（1 基）；恰在锚点上归属其右侧段，末锚点归属最后一段。 */
    private static int segmentIndex(long[] anchorTimes, long time) {
        for (int i = 0; i < anchorTimes.length - 1; i++) {
            if (time <= anchorTimes[i + 1]) {
                return i + 1;
            }
        }
        return anchorTimes.length - 1;
    }

    private static AnchorPlan findAnchorAt(List<AnchorPlan> anchors, String readingId) {
        for (AnchorPlan anchor : anchors) {
            if (anchor.readingId().equals(readingId)) {
                return anchor;
            }
        }
        return null;
    }

    /** 毫小时域线性插值：v1 + (v2 - v1) * (t - t1) / (t2 - t1)，四舍五入到 0.001 小时后换算毫秒。 */
    private static long interpolateMillis(long t1, long v1MilliHours, long t2, long v2MilliHours,
                                          long time) {
        BigInteger numerator = BigInteger.valueOf(v2MilliHours - v1MilliHours)
                .multiply(BigInteger.valueOf(time - t1));
        BigInteger denominator = BigInteger.valueOf(t2 - t1);
        BigInteger quotient = numerator.divide(denominator);
        BigInteger remainder = numerator.remainder(denominator);
        if (remainder.shiftLeft(1).compareTo(denominator) >= 0) {
            quotient = quotient.add(BigInteger.ONE);
        }
        return MeterUnits.milliHoursToMillis(v1MilliHours + quotient.longValueExact());
    }

    private static long nanos(Instant instant) {
        return instant.getEpochSecond() * 1_000_000_000L + instant.getNano();
    }

    /** 毫秒 → 小时十进制字符串（固定 3 位小数）；入参须为 0.001 小时对齐的值。 */
    private static String hoursString(long millis) {
        return BigDecimal.valueOf(MeterUnits.millisToMilliHoursExact(millis), 3).toPlainString();
    }

    private static List<DriftAnchorView> toAnchorViews(List<AnchorPlan> anchors) {
        return anchors.stream()
                .map(anchor -> new DriftAnchorView(anchor.seq(), anchor.readingId(),
                        anchor.sampledAt(), anchor.expectedVersion(), anchor.calibratedMillis(),
                        hoursString(anchor.calibratedMillis())))
                .toList();
    }

    private static List<DriftCorrectionItemView> toItemViews(List<ItemPlan> items) {
        return items.stream()
                .map(item -> new DriftCorrectionItemView(item.seq(), item.readingId(),
                        item.sampledAt(), item.segmentIndex(),
                        MeterUnits.millisToMinutesRounded(item.oldMillis()), item.oldMillis(),
                        item.newMillis(), hoursString(item.newMillis()),
                        item.oldRevisionNo(), item.newRevisionNo()))
                .toList();
    }

    private static MaintenanceItemView toMaintenanceItemView(MaintenanceItemPlan item) {
        return new MaintenanceItemView(item.itemKey(), item.periodMinutes(),
                item.runMillisBefore(), item.statusBefore(), item.nextThresholdMillisBefore(),
                item.runMillisAfter(), item.statusAfter(), item.nextThresholdMillisAfter());
    }

    private ApiException equipmentNotFound(String equipmentId) {
        return ApiException.notFound("EQUIPMENT_NOT_FOUND", "设备不存在：" + equipmentId);
    }
}
