package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddMaintenanceItemRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.ItemStatusResponse;
import com.example.starter.maintenance.api.dto.ItemStatusSummaryResponse;
import com.example.starter.maintenance.api.dto.MaintenanceItemResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.domain.Equipment;
import com.example.starter.maintenance.domain.MaintenanceItem;
import com.example.starter.maintenance.domain.MaintenanceRecord;
import com.example.starter.maintenance.domain.Reading;
import com.example.starter.maintenance.store.EquipmentRepository;

/**
 * 设备工时保养事务业务服务。写操作流程：设备行锁 → 幂等判定 → 版本校验 → 业务规则 → 变更并版本加一。
 * 去重记录与业务变更同事务提交；任一规则失败抛异常整体回滚。
 *
 * <p>多保养项目：登记设备时迁移生成 DEFAULT 项目，旧接口（读数修订除外）与旧状态/历史
 * 接口均作用于 DEFAULT；读数由全部项目共享，各项目独立维护锚点与状态；
 * 被任一项目保养记录引用的读数不可修订。
 */
@Service
public class EquipmentTxService {

    /** 一台设备除 DEFAULT 外允许新增的项目上限。 */
    private static final int MAX_EXTRA_ITEMS = 20;

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
                    Instant now = clock.instant();
                    repository.insertEquipment(req.equipmentId(), req.maintenancePeriodMinutes(), now);
                    // 登记时的原保养周期迁移为 DEFAULT 项目
                    repository.insertItem(new MaintenanceItem(req.equipmentId(), MaintenanceItem.DEFAULT,
                            req.maintenancePeriodMinutes(), now));
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    // ---------- 新增保养项目 ----------

    @Transactional
    public MaintenanceItemResponse addItem(String equipmentId, AddMaintenanceItemRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.itemCode() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "ADD_MAINTENANCE_ITEM", fingerprint,
                MaintenanceItemResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    if (repository.findItem(equipmentId, req.itemCode()).isPresent()) {
                        throw ApiException.conflict("ITEM_EXISTS",
                                "保养项目已存在：" + req.itemCode() + "（DEFAULT 为登记时迁移项目，不可重复创建）");
                    }
                    if (repository.countItems(equipmentId) >= 1 + MAX_EXTRA_ITEMS) {
                        throw ApiException.unprocessable("ITEM_LIMIT_EXCEEDED",
                                "每台设备最多 " + MAX_EXTRA_ITEMS + " 个新增保养项目（不含 DEFAULT）");
                    }
                    Instant now = clock.instant();
                    repository.insertItem(new MaintenanceItem(equipmentId, req.itemCode(),
                            req.maintenancePeriodMinutes(), now));
                    repository.incrementVersion(equipmentId);
                    // 新增项目不补造保养记录：状态立即按设备现有最新读数从 0 锚点计算
                    return new MaintenanceItemResponse(equipmentId, req.itemCode(),
                            req.maintenancePeriodMinutes(), now, equipment.version() + 1);
                });
    }

    // ---------- 新增读数 ----------

    @Transactional
    public ReadingResponse addReading(String equipmentId, AddReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.readingId() + "|" + req.sampledAt()
                + "|" + req.cumulativeMinutes() + "|" + req.expectedVersion();
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
                                    req.cumulativeMinutes(), 1),
                            now);
                    repository.insertRevision(equipmentId, req.readingId(), 1,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, req.readingId(), req.sampledAt(),
                            req.cumulativeMinutes(), 1, false, equipment.version() + 1);
                });
    }

    // ---------- 修订读数 ----------

    @Transactional
    public ReadingResponse reviseReading(String equipmentId, String readingId, ReviseReadingRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + readingId + "|" + req.cumulativeMinutes()
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "REVISE_READING", fingerprint,
                ReadingResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    Reading reading = repository.findReading(equipmentId, readingId)
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "读数不存在：" + readingId));
                    // 被至少一个项目保养记录引用的读数不可修订，409 列出引用它的全部 itemCode
                    List<String> anchoredItems = repository.findItemCodesAnchoringReading(equipmentId, readingId);
                    if (!anchoredItems.isEmpty()) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已被保养记录引用，不可修订：" + readingId,
                                new AnchoredDetails(readingId, anchoredItems));
                    }
                    checkMonotonic(equipmentId, reading.sampledAt(), req.cumulativeMinutes());
                    int newRevisionNo = reading.revisionNo() + 1;
                    Instant now = clock.instant();
                    repository.updateReadingValue(equipmentId, readingId, req.cumulativeMinutes(),
                            newRevisionNo, now);
                    repository.insertRevision(equipmentId, readingId, newRevisionNo,
                            req.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new ReadingResponse(equipmentId, readingId, reading.sampledAt(),
                            req.cumulativeMinutes(), newRevisionNo, false, equipment.version() + 1);
                });
    }

    // ---------- 完成保养（按项目） ----------

    @Transactional
    public MaintenanceResponse completeMaintenance(String equipmentId, CompleteMaintenanceRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String itemCode = resolveItemCode(req.itemCode());
        String fingerprint = equipmentId + "|" + itemCode + "|" + req.readingId() + "|"
                + req.anchorRevisionNo() + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "COMPLETE_MAINTENANCE", fingerprint,
                MaintenanceResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    MaintenanceItem item = repository.findItem(equipmentId, itemCode)
                            .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND",
                                    "保养项目不存在：" + itemCode));
                    Reading anchor = repository.findReading(equipmentId, req.readingId())
                            .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND",
                                    "锚点读数不存在：" + req.readingId()));
                    if (anchor.revisionNo() != req.anchorRevisionNo()) {
                        throw ApiException.conflict("ANCHOR_REVISION_CONFLICT",
                                "锚点修订号与读数当前修订号不一致：期望 " + req.anchorRevisionNo()
                                        + "，当前 " + anchor.revisionNo());
                    }
                    // 锚点时间只要求晚于“该项目”上次锚点；不同项目可锚定同一读数
                    Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId, itemCode);
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于该项目上次保养锚点时间");
                    }
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, itemCode, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, itemCode, req.readingId(),
                            req.anchorRevisionNo(), anchor.sampledAt(), anchor.cumulativeMinutes(),
                            now, equipment.version() + 1);
                });
    }

    // ---------- 查询 ----------

    /** 旧接口：DEFAULT 项目状态，响应结构保持兼容。 */
    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceItem defaultItem = requireItem(equipmentId, MaintenanceItem.DEFAULT);
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last =
                repository.findLastMaintenance(equipmentId, MaintenanceItem.DEFAULT);
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= defaultItem.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new StatusResponse(equipmentId, equipment.version(), defaultItem.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status);
    }

    /** 单项目状态：读数共享设备最新值，锚点取该项目最近一次保养。 */
    @Transactional(readOnly = true)
    public ItemStatusResponse getItemStatus(String equipmentId, String rawItemCode) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        String itemCode = resolveItemCode(rawItemCode);
        MaintenanceItem item = requireItem(equipmentId, itemCode);
        return buildItemStatus(equipmentId, item);
    }

    /** 全项目汇总：按 itemCode 字典序排序。 */
    @Transactional(readOnly = true)
    public ItemStatusSummaryResponse getItemStatusSummary(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        List<MaintenanceItem> items = repository.listItems(equipmentId);
        List<ItemStatusResponse> statuses = items.stream()
                .map(item -> buildItemStatus(equipmentId, item))
                .toList();
        return new ItemStatusSummaryResponse(equipmentId, equipment.version(), statuses);
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(),
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
                        row.requestId(), row.createdAt()))
                .toList();
    }

    /** 旧接口：DEFAULT 项目保养历史，按锚点时间升序稳定排序。 */
    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        return listItemMaintenances(equipmentId, MaintenanceItem.DEFAULT);
    }

    /** 单项目保养历史，按锚点时间升序稳定排序（同刻再按记录主键升序）。 */
    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listItemMaintenances(String equipmentId, String rawItemCode) {
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        String itemCode = resolveItemCode(rawItemCode);
        requireItem(equipmentId, itemCode);
        return repository.listMaintenances(equipmentId, itemCode).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId, itemCode,
                        record.readingId(), record.anchorRevisionNo(), record.anchorSampledAt(),
                        record.anchorCumulativeMinutes(), record.completedAt(), 0L))
                .toList();
    }

    // ---------- 内部规则 ----------

    private ItemStatusResponse buildItemStatus(String equipmentId, MaintenanceItem item) {
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last =
                repository.findLastMaintenance(equipmentId, item.itemCode());
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last.map(MaintenanceRecord::anchorCumulativeMinutes).orElse(0L);
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= item.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new ItemStatusResponse(item.itemCode(), item.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last.map(MaintenanceRecord::anchorSampledAt).orElse(null), anchorCumulative,
                runMinutes, status);
    }

    private MaintenanceItem requireItem(String equipmentId, String itemCode) {
        return repository.findItem(equipmentId, itemCode)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND",
                        "保养项目不存在：" + itemCode));
    }

    /** 旧接口请求不带 itemCode（或空串）时按 DEFAULT 项目处理。 */
    private String resolveItemCode(String itemCode) {
        if (itemCode == null || itemCode.isBlank()) {
            return MaintenanceItem.DEFAULT;
        }
        return itemCode;
    }

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

    /** 修订拦截 409 的结构化明细：列出引用该读数的全部 itemCode。 */
    public record AnchoredDetails(String readingId, List<String> itemCodes) {
    }
}
