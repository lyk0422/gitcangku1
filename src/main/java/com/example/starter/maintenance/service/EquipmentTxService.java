package com.example.starter.maintenance.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.maintenance.api.ApiException;
import com.example.starter.maintenance.api.dto.AddItemRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.ItemStatusSummaryResponse;
import com.example.starter.maintenance.api.dto.ItemStatusView;
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
 * 所有项目共享同一组累计工时读数，独立维护各自的最近保养锚点、运行分钟与状态。
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

    // ---------- 登记设备（同时迁移生成 DEFAULT 项目） ----------

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
                    // 登记时的原保养周期迁移为 itemCode=DEFAULT 的项目。
                    repository.insertItem(req.equipmentId(), MaintenanceItem.DEFAULT_ITEM_CODE,
                            req.maintenancePeriodMinutes(), now);
                    return new EquipmentResponse(req.equipmentId(), req.maintenancePeriodMinutes(), 1L);
                });
    }

    // ---------- 新增保养项目 ----------

    @Transactional
    public MaintenanceItemResponse addItem(String equipmentId, AddItemRequest req) {
        Equipment equipment = lockEquipment(equipmentId);
        String fingerprint = equipmentId + "|" + req.itemCode() + "|" + req.maintenancePeriodMinutes()
                + "|" + req.expectedVersion();
        return idempotency.execute(req.requestId(), "ADD_ITEM", fingerprint,
                MaintenanceItemResponse.class, () -> {
                    checkVersion(equipment, req.expectedVersion());
                    String itemCode = req.itemCode();
                    if (MaintenanceItem.DEFAULT_ITEM_CODE.equalsIgnoreCase(itemCode)) {
                        throw ApiException.conflict("ITEM_CODE_RESERVED",
                                "DEFAULT 为登记时迁移项目的保留编码，不可新增");
                    }
                    if (repository.findItem(equipmentId, itemCode).isPresent()) {
                        throw ApiException.conflict("ITEM_EXISTS", "保养项目已存在：" + itemCode);
                    }
                    if (repository.countItems(equipmentId) >= MaintenanceItem.MAX_ITEMS_PER_EQUIPMENT) {
                        throw ApiException.unprocessable("ITEM_LIMIT_EXCEEDED",
                                "每台设备最多 " + (MaintenanceItem.MAX_ITEMS_PER_EQUIPMENT - 1)
                                        + " 个自定义保养项目（含 DEFAULT 共 "
                                        + MaintenanceItem.MAX_ITEMS_PER_EQUIPMENT + " 个）");
                    }
                    Instant now = clock.instant();
                    repository.insertItem(equipmentId, itemCode, req.maintenancePeriodMinutes(), now);
                    repository.incrementVersion(equipmentId);
                    // 新增项目立即按现有最新读数计算状态（查询时即时计算），不补造任何保养记录。
                    return new MaintenanceItemResponse(equipmentId, itemCode,
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
                    // 被任意项目的保养记录引用的读数都不可修订，409 列出全部引用项目。
                    List<String> anchoredItemCodes = repository.findItemCodesAnchoringReading(
                            equipmentId, readingId);
                    if (!anchoredItemCodes.isEmpty()) {
                        throw ApiException.conflict("READING_ANCHORED",
                                "读数已被保养项目锚定，不可修订：" + readingId
                                        + "；引用它的 itemCode=" + anchoredItemCodes);
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

    // ---------- 完成保养 ----------

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
                    // 锚点时间只与该项目自己的上次锚点比较；同一读数可作为不同项目的锚点。
                    Optional<MaintenanceRecord> last =
                            repository.findLastMaintenance(equipmentId, item.itemCode());
                    if (last.isPresent() && !anchor.sampledAt().isAfter(last.get().anchorSampledAt())) {
                        throw ApiException.unprocessable("ANCHOR_TIME_NOT_LATER",
                                "保养锚点时间必须晚于该项目（" + item.itemCode()
                                        + "）上次保养锚点时间");
                    }
                    Instant now = clock.instant();
                    long maintenanceId = repository.insertMaintenance(equipmentId, item.itemCode(),
                            req.readingId(), req.anchorRevisionNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), req.requestId(), now);
                    repository.incrementVersion(equipmentId);
                    return new MaintenanceResponse(maintenanceId, equipmentId, item.itemCode(),
                            req.readingId(), req.anchorRevisionNo(), anchor.sampledAt(),
                            anchor.cumulativeMinutes(), now, equipment.version() + 1);
                });
    }

    // ---------- 查询：状态 ----------

    /** 旧接口：DEFAULT 项目状态，响应结构保持兼容。 */
    @Transactional(readOnly = true)
    public StatusResponse getStatus(String equipmentId) {
        return getItemStatusView(equipmentId, MaintenanceItem.DEFAULT_ITEM_CODE).toLegacyStatus(equipmentId);
    }

    /** 单项目状态。 */
    @Transactional(readOnly = true)
    public ItemStatusView getItemStatus(String equipmentId, String rawItemCode) {
        String itemCode = resolveItemCode(rawItemCode);
        return getItemStatusView(equipmentId, itemCode).view();
    }

    /** 全项目汇总：按 itemCode 升序。 */
    @Transactional(readOnly = true)
    public ItemStatusSummaryResponse getItemsStatus(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        List<MaintenanceItem> items = repository.listItems(equipmentId);
        List<ItemStatusView> views = items.stream()
                .map(item -> buildItemStatus(item, latest,
                        repository.findLastMaintenance(equipmentId, item.itemCode()).orElse(null)))
                .toList();
        return new ItemStatusSummaryResponse(equipmentId, equipment.version(), views);
    }

    // ---------- 查询：项目与历史 ----------

    @Transactional(readOnly = true)
    public List<MaintenanceItemResponse> listItems(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listItems(equipmentId).stream()
                .map(item -> new MaintenanceItemResponse(equipmentId, item.itemCode(),
                        item.maintenancePeriodMinutes(), item.createdAt(), equipment.version()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReadingResponse> listReadings(String equipmentId) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        return repository.listReadings(equipmentId).stream()
                .map(reading -> new ReadingResponse(equipmentId, reading.readingId(), reading.sampledAt(),
                        reading.cumulativeMinutes(), reading.revisionNo(),
                        !repository.findItemCodesAnchoringReading(equipmentId, reading.readingId()).isEmpty(),
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

    /** 旧接口：DEFAULT 项目保养历史。 */
    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listMaintenances(String equipmentId) {
        return listItemMaintenances(equipmentId, MaintenanceItem.DEFAULT_ITEM_CODE);
    }

    /** 单项目保养历史，按锚点时间稳定升序。 */
    @Transactional(readOnly = true)
    public List<MaintenanceResponse> listItemMaintenances(String equipmentId, String rawItemCode) {
        String itemCode = resolveItemCode(rawItemCode);
        repository.findEquipment(equipmentId).orElseThrow(() -> equipmentNotFound(equipmentId));
        repository.findItem(equipmentId, itemCode)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "保养项目不存在：" + itemCode));
        return repository.listMaintenances(equipmentId, itemCode).stream()
                .map(record -> new MaintenanceResponse(record.maintenanceId(), equipmentId,
                        record.itemCode(), record.readingId(), record.anchorRevisionNo(),
                        record.anchorSampledAt(), record.anchorCumulativeMinutes(),
                        record.completedAt(), 0L))
                .toList();
    }

    // ---------- 内部规则 ----------

    /** 解析请求中的项目编码：空值/空白沿用旧接口语义操作 DEFAULT。 */
    private String resolveItemCode(String rawItemCode) {
        if (rawItemCode == null || rawItemCode.isBlank()) {
            return MaintenanceItem.DEFAULT_ITEM_CODE;
        }
        return rawItemCode;
    }

    private record ItemStatusBundle(ItemStatusView view, long version, MaintenanceItem item) {
        StatusResponse toLegacyStatus(String equipmentId) {
            return new StatusResponse(equipmentId, version, item.maintenancePeriodMinutes(),
                    view.latestSampledAt(), view.latestCumulativeMinutes(),
                    view.lastMaintenanceAnchorSampledAt(),
                    view.lastMaintenanceAnchorCumulativeMinutes(), view.runMinutes(), view.status());
        }
    }

    private ItemStatusBundle getItemStatusView(String equipmentId, String itemCode) {
        Equipment equipment = repository.findEquipment(equipmentId)
                .orElseThrow(() -> equipmentNotFound(equipmentId));
        MaintenanceItem item = repository.findItem(equipmentId, itemCode)
                .orElseThrow(() -> ApiException.notFound("ITEM_NOT_FOUND", "保养项目不存在：" + itemCode));
        Optional<Reading> latest = repository.findLatestReading(equipmentId);
        Optional<MaintenanceRecord> last = repository.findLastMaintenance(equipmentId, itemCode);
        return new ItemStatusBundle(buildItemStatus(item, latest, last.orElse(null)),
                equipment.version(), item);
    }

    /**
     * 计算单项目状态：最新读数（全项目共享）减该项目最近锚点工时，无锚点则从 0 计算；
     * 达到该项目自己的周期即 DUE。
     */
    private ItemStatusView buildItemStatus(MaintenanceItem item, Optional<Reading> latest,
                                           MaintenanceRecord last) {
        long latestCumulative = latest.map(Reading::cumulativeMinutes).orElse(0L);
        long anchorCumulative = last == null ? 0L : last.anchorCumulativeMinutes();
        long runMinutes = latestCumulative - anchorCumulative;
        String status = runMinutes >= item.maintenancePeriodMinutes() ? "DUE" : "OK";
        return new ItemStatusView(item.itemCode(), item.maintenancePeriodMinutes(),
                latest.map(Reading::sampledAt).orElse(null), latestCumulative,
                last == null ? null : last.anchorSampledAt(), anchorCumulative, runMinutes, status);
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
}
