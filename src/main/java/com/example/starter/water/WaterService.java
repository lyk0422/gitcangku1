package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.BlendItemRow;
import com.example.starter.water.WaterRepository.BlendSnapshotRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SourceRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationBlendSummaryResponse;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.BlendItemRequest;
import com.example.starter.water.dto.Dtos.BlendItemResponse;
import com.example.starter.water.dto.Dtos.BlendSnapshotListResponse;
import com.example.starter.water.dto.Dtos.BlendSnapshotResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SourceResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.UpdateSourceSalinityRequest;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 灌区配水业务服务。
 *
 * <p>幂等：每个写命令携带 commandKey，事务内先占位插入命令行再执行业务并写回响应；
 * 同键同参重放返回首次结果，同键改参返回 409。</p>
 *
 * <p>并发：批准申请与创建/取消限供在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序生效，保证已批准总量永不超过最终可用总量。</p>
 */
@Service
public class WaterService {

    static final String STATUS_REQUESTED = "REQUESTED";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_CANCELLED = "CANCELLED";

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern SALINITY_PATTERN = Pattern.compile("\\d{1,9}(\\.\\d{1,3})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");
    private static final int BLEND_MIN_SOURCES = 1;
    private static final int BLEND_MAX_SOURCES = 5;
    private static final int SALINITY_SCALE = 6;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 命令入口
    // ------------------------------------------------------------------

    /** 创建供水窗口。 */
    public WindowResponse createWindow(String commandKey, String windowKey, String channelId,
                                       String startUtc, String endUtc, String plannedVolume) {
        requireKey("commandKey", commandKey);
        requireKey("windowKey", windowKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        BigDecimal planned = parseAmount("plannedVolume", plannedVolume);
        String params = "WINDOW_CREATE|" + windowKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + planned.toPlainString();
        return runCommand("WINDOW_CREATE", commandKey, params, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor；salinityLimit 为可选盐度上限（mg/L），null/空表示不限制。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String salinityLimit,
                                               String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        BigDecimal limit = parseOptionalSalinity("salinityLimit", salinityLimit);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + (limit == null ? "" : limit.toPlainString()) + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, limit, actor, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：加入本申请后已批准总量不得超过当前可用总量，否则 422。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String params = "ALLOCATION_APPROVE|" + allocationKey;
        return runCommand("ALLOCATION_APPROVE", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/取消并发时使用过期状态与持有额度
            allocation = repository.lockAllocationByKey(allocationKey);
            switch (allocation.status()) {
                case STATUS_CANCELLED ->
                        throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能批准");
                case STATUS_APPROVED ->
                        throw ApiException.conflict("ALLOCATION_ALREADY_APPROVED", "申请已批准，不能重复批准");
                default -> {
                }
            }
            BigDecimal available = availableTotal(window);
            BigDecimal approved = repository.sumApprovedAmount(window.id());
            if (approved.add(allocation.amount()).compareTo(available) > 0) {
                throw ApiException.quotaExceeded("批准后将超过当前可用总量 " + fmt(available));
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 取消申请：仅申请人本人可取消 REQUESTED/APPROVED，取消不可恢复并立即释放水量。 */
    public AllocationResponse cancelAllocation(String commandKey, String allocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "ALLOCATION_CANCEL|" + allocationKey + "|" + actor;
        return runCommand("ALLOCATION_CANCEL", commandKey, params, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/并发取消使用过期状态
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以取消该申请");
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_ALREADY_CANCELLED", "申请已取消，不能重复取消");
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /**
     * 同窗口额度原子转让：源申请人（actor）把自己 APPROVED 申请的额度转给同窗口、不同用水户的
     * REQUESTED 目标申请，转让额等于目标原申请水量的全部额度。
     * 源扣减、目标批准、不可变流水在同一事务完成，窗口已批准总量不变；失败回滚无任何额度变化。
     */
    public TransferResponse transferAllocation(String commandKey, String transferKey, String sourceAllocationKey,
                                               String targetAllocationKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("transferKey", transferKey);
        requireKey("sourceAllocationKey", sourceAllocationKey);
        requireKey("targetAllocationKey", targetAllocationKey);
        requireKey("X-Actor-Id", actor);
        String params = "TRANSFER|" + transferKey + "|" + sourceAllocationKey + "|" + targetAllocationKey + "|"
                + actor;
        return runCommand("TRANSFER", commandKey, params, TransferResponse.class, () -> {
            if (repository.findTransferByKey(transferKey) != null) {
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            AllocationRow source = repository.findAllocationByKey(sourceAllocationKey);
            if (source == null) {
                throw ApiException.notFound("SOURCE_ALLOCATION_NOT_FOUND",
                        "转出申请不存在: " + sourceAllocationKey);
            }
            AllocationRow target = repository.findAllocationByKey(targetAllocationKey);
            if (target == null) {
                throw ApiException.notFound("TARGET_ALLOCATION_NOT_FOUND",
                        "转入申请不存在: " + targetAllocationKey);
            }
            // 先锁窗口行，与普通批准、取消、限供调整按事务提交顺序串行裁决
            lockWindowOf(source);
            // 窗口锁内再锁定源/目标申请行，得到最新状态与持有额度
            AllocationRow lockedSource = repository.lockAllocationByKey(sourceAllocationKey);
            AllocationRow lockedTarget = repository.lockAllocationByKey(targetAllocationKey);
            if (!lockedSource.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有转出申请人本人可以发起转让");
            }
            if (!STATUS_APPROVED.equals(lockedSource.status())) {
                throw ApiException.conflict("SOURCE_NOT_APPROVED", "转出申请必须为 APPROVED 状态");
            }
            if (!STATUS_REQUESTED.equals(lockedTarget.status())) {
                throw ApiException.conflict("TARGET_NOT_REQUESTED", "转入申请必须为 REQUESTED 状态");
            }
            if (lockedSource.windowId() != lockedTarget.windowId()) {
                throw ApiException.conflict("DIFFERENT_WINDOW", "转让双方必须属于同一供水窗口");
            }
            if (lockedSource.userId().equals(lockedTarget.userId())) {
                throw ApiException.conflict("SAME_USER", "转让双方必须属于不同用水户");
            }
            BigDecimal amount = lockedTarget.amount();
            if (lockedSource.heldAmount().compareTo(amount) < 0) {
                throw ApiException.quotaExceeded("源申请当前持有额度 " + fmt(lockedSource.heldAmount())
                        + " 不足，无法转让 " + fmt(amount));
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            try {
                repository.insertTransfer(transferKey, lockedSource.windowId(), sourceAllocationKey,
                        targetAllocationKey, amount, actor, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 transferKey（换 commandKey）：事务回滚，额度无变化
                throw ApiException.conflict("TRANSFER_KEY_REUSED", "transferKey 已被使用: " + transferKey);
            }
            return toTransferResponse(repository.findTransferByKey(transferKey));
        });
    }

    /** 查询窗口全部转让流水（不可变），按发生顺序返回。 */
    public TransferListResponse getTransfers(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<TransferResponse> transfers = repository.listTransfers(windowId).stream()
                .map(this::toTransferResponse).toList();
        return new TransferListResponse(windowId, transfers);
    }

    /** 创建限供：仅当当前已批准总量不超过拟定限供水量时允许。 */
    public CurtailmentResponse createCurtailment(String commandKey, long windowId, String volume) {
        requireKey("commandKey", commandKey);
        BigDecimal qty = parseAmount("volume", volume);
        String params = "CURTAILMENT_CREATE|" + windowId + "|" + qty.toPlainString();
        return runCommand("CURTAILMENT_CREATE", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (qty.compareTo(window.plannedVolume()) > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "限供水量不能超过计划水量 " + fmt(window.plannedVolume()));
            }
            if (repository.findActiveCurtailment(windowId) != null) {
                throw ApiException.conflict("CURTAILMENT_EXISTS", "窗口已存在生效中的限供");
            }
            BigDecimal approved = repository.sumApprovedAmount(windowId);
            if (approved.compareTo(qty) > 0) {
                throw ApiException.conflict("CURTAILMENT_BELOW_APPROVED",
                        "当前已批准总量 " + fmt(approved) + " 超过拟定限供水量 " + fmt(qty));
            }
            long id = repository.insertCurtailment(windowId, qty, nowNanos());
            return toCurtailmentResponse(repository.findActiveCurtailment(windowId));
        });
    }

    /** 取消当前生效限供，恢复计划水量。 */
    public CurtailmentResponse cancelCurtailment(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = "CURTAILMENT_CANCEL|" + windowId;
        return runCommand("CURTAILMENT_CANCEL", commandKey, params, CurtailmentResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            CurtailmentRow active = repository.findActiveCurtailment(windowId);
            if (active == null) {
                throw ApiException.conflict("NO_ACTIVE_CURTAILMENT", "窗口没有生效中的限供");
            }
            repository.cancelCurtailment(active.id(), nowNanos());
            List<CurtailmentRow> all = repository.listCurtailments(windowId);
            return all.stream().filter(c -> c.id() == active.id()).findFirst()
                    .map(this::toCurtailmentResponse).orElseThrow();
        });
    }

    /** 查询窗口当前可用容量。 */
    public CapacityResponse getCapacity(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        BigDecimal available = active != null ? active.volume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        return new CapacityResponse(window.id(), fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), fmt(approved),
                fmt(available.subtract(approved)));
    }

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供。 */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        List<AllocationResponse> allocations = repository.listAllocations(windowId).stream()
                .map(this::toAllocationResponse).toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments);
    }

    // ------------------------------------------------------------------
    // 水源与水质掺配
    // ------------------------------------------------------------------

    /** 注册水源：可用水量允许为 0，盐度非负，单位 mg/L。 */
    public SourceResponse createSource(String commandKey, String sourceKey, String availableAmount,
                                       String salinity) {
        requireKey("commandKey", commandKey);
        requireKey("sourceKey", sourceKey);
        BigDecimal amount = parseNonNegativeAmount("availableAmount", availableAmount);
        BigDecimal salt = parseSalinity("salinity", salinity);
        String params = "SOURCE_CREATE|" + sourceKey + "|" + amount.toPlainString() + "|" + salt.toPlainString();
        return runCommand("SOURCE_CREATE", commandKey, params, SourceResponse.class, () -> {
            if (repository.findSourceByKey(sourceKey) != null) {
                throw ApiException.conflict("SOURCE_KEY_EXISTS", "水源已存在: " + sourceKey);
            }
            repository.insertSource(sourceKey, amount, salt, nowNanos());
            return toSourceResponse(repository.findSourceByKey(sourceKey));
        });
    }

    /**
     * 携带期望版本修改水源盐度：expectedVersion 与当前版本不符返回 409。
     * 修改只影响后续核销，历史掺配快照明细盐度已冻结，不改写。
     */
    public SourceResponse updateSourceSalinity(String commandKey, String sourceKey, Long expectedVersion,
                                               String salinity) {
        requireKey("commandKey", commandKey);
        requireKey("sourceKey", sourceKey);
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空且必须非负");
        }
        BigDecimal salt = parseSalinity("salinity", salinity);
        String params = "SOURCE_UPDATE_SALINITY|" + sourceKey + "|" + expectedVersion + "|" + salt.toPlainString();
        return runCommand("SOURCE_UPDATE_SALINITY", commandKey, params, SourceResponse.class, () -> {
            SourceRow existing = repository.findSourceByKey(sourceKey);
            if (existing == null) {
                throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + sourceKey);
            }
            int updated = repository.updateSourceSalinityIfVersion(sourceKey, salt, expectedVersion, nowNanos());
            if (updated == 0) {
                SourceRow current = repository.findSourceByKey(sourceKey);
                throw ApiException.conflict("SOURCE_VERSION_CONFLICT",
                        "水源版本 " + expectedVersion + " 已过期，当前版本 " + current.version()
                                + "，当前盐度 " + fmt(current.salinity()) + " mg/L");
            }
            return toSourceResponse(repository.findSourceByKey(sourceKey));
        });
    }

    /** 查询水源当前余量与盐度。 */
    public SourceResponse getSource(String sourceKey) {
        requireKey("sourceKey", sourceKey);
        SourceRow source = repository.findSourceByKey(sourceKey);
        if (source == null) {
            throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + sourceKey);
        }
        return toSourceResponse(source);
    }

    /**
     * 水质掺配核销：在同一事务内校验并扣减全部水源可用量与申请剩余额度，写入不可变快照。
     * 任一水源余量不足、取水量合计不等于核销量或加权平均盐度高于申请上限均 422 整单回滚；
     * 任一扣减失败整单回滚。expectedAllocationVersion 必须与申请当前版本一致（并发转让/核销/取消会推进版本），
     * 否则 409。水源集合按 source_key 升序规范化，换序视为同参。
     */
    public BlendSnapshotResponse blendWriteOff(String commandKey, String blendKey, String allocationKey,
                                               Long expectedAllocationVersion, String writeOffAmount,
                                               List<BlendItemRequest> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("blendKey", blendKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        if (expectedAllocationVersion == null || expectedAllocationVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationVersion 不能为空且必须非负");
        }
        BigDecimal total = parseAmount("writeOffAmount", writeOffAmount);
        if (items == null || items.size() < BLEND_MIN_SOURCES || items.size() > BLEND_MAX_SOURCES) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "items 必须包含 " + BLEND_MIN_SOURCES + " 至 " + BLEND_MAX_SOURCES + " 个水源");
        }
        // 规范化：按 source_key 升序；同一核销内水源不得重复
        TreeMap<String, BigDecimal> normalized = new TreeMap<>();
        for (BlendItemRequest item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能包含空元素");
            }
            requireKey("items.sourceKey", item.sourceKey());
            BigDecimal qty = parseAmount("items.amount", item.amount());
            if (normalized.put(item.sourceKey(), qty) != null) {
                throw ApiException.badRequest("DUPLICATE_SOURCE",
                        "同一核销内水源不得重复: " + item.sourceKey());
            }
        }
        String params = blendParams(actor, allocationKey, expectedAllocationVersion, total, normalized);
        return runCommand("BLEND_WRITE_OFF", commandKey, params, BlendSnapshotResponse.class, () -> {
            if (repository.findBlendSnapshotByKey(blendKey) != null) {
                throw ApiException.conflict("BLEND_KEY_REUSED", "blendKey 已被使用: " + blendKey);
            }
            AllocationRow allocation = repository.lockAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            // 申请版本裁决：与转让/取消/并发核销按提交顺序串行，版本被推进即拒绝
            if (allocation.version() != expectedAllocationVersion) {
                throw ApiException.conflict("ALLOCATION_VERSION_CONFLICT",
                        "申请版本 " + expectedAllocationVersion + " 已过期，当前版本 " + allocation.version());
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能核销");
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED", "只有 APPROVED 的申请可以核销");
            }
            if (allocation.heldAmount().compareTo(total) < 0) {
                throw ApiException.unprocessable("INSUFFICIENT_ALLOCATION_HELD",
                        "申请剩余额度 " + fmt(allocation.heldAmount()) + " 不足，无法核销 " + fmt(total));
            }
            // 按规范化顺序一次性锁定水源行，与盐度修改、其它核销按事务提交顺序串行裁决
            List<String> sourceKeys = List.copyOf(normalized.keySet());
            List<SourceRow> locked = repository.lockSourcesByKeys(sourceKeys);
            if (locked.size() != sourceKeys.size()) {
                List<String> found = locked.stream().map(SourceRow::sourceKey).sorted().toList();
                List<String> missing = new ArrayList<>(sourceKeys);
                missing.removeAll(found);
                throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + missing);
            }
            Map<String, SourceRow> rows = new LinkedHashMap<>();
            for (SourceRow row : locked) {
                rows.put(row.sourceKey(), row);
            }
            // 1) 各水源可用量必须充足
            for (Map.Entry<String, BigDecimal> entry : normalized.entrySet()) {
                SourceRow source = rows.get(entry.getKey());
                if (source.availableAmount().compareTo(entry.getValue()) < 0) {
                    throw ApiException.unprocessable("SOURCE_INSUFFICIENT",
                            "水源 " + entry.getKey() + " 余量 " + fmt(source.availableAmount())
                                    + " 不足，本次拟取 " + fmt(entry.getValue()));
                }
            }
            // 2) 取水量合计必须等于申请核销量（总量守恒）
            BigDecimal itemSum = normalized.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (itemSum.compareTo(total) != 0) {
                throw ApiException.unprocessable("BLEND_TOTAL_MISMATCH",
                        "各水源取水量合计 " + fmt(itemSum) + " 不等于申请核销量 " + fmt(total));
            }
            // 3) 加权平均盐度不得高于申请上限
            BigDecimal weighted = weightedSalinity(normalized, rows, total);
            if (allocation.salinityLimit() != null
                    && weighted.compareTo(allocation.salinityLimit()) > 0) {
                throw ApiException.unprocessable("SALINITY_EXCEEDED",
                        "加权平均盐度 " + fmt(weighted) + " mg/L 高于申请上限 "
                                + fmt(allocation.salinityLimit()) + " mg/L");
            }
            // 全部校验通过：跨表原子扣减 + 不可变快照，任一失败由事务整单回滚
            long now = nowNanos();
            for (Map.Entry<String, BigDecimal> entry : normalized.entrySet()) {
                repository.decrementSourceAmount(rows.get(entry.getKey()).id(), entry.getValue());
            }
            repository.decrementHeldAmount(allocation.id(), total, now);
            long snapshotId;
            try {
                snapshotId = repository.insertBlendSnapshot(blendKey, allocationKey, actor, total,
                        weighted, allocation.salinityLimit(), expectedAllocationVersion, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 blendKey（换 commandKey）：事务回滚，水源与额度无变化
                throw ApiException.conflict("BLEND_KEY_REUSED", "blendKey 已被使用: " + blendKey);
            }
            int ordinal = 0;
            for (Map.Entry<String, BigDecimal> entry : normalized.entrySet()) {
                repository.insertBlendItem(snapshotId, entry.getKey(), entry.getValue(),
                        rows.get(entry.getKey()).salinity(), ordinal++);
            }
            return toBlendSnapshotResponse(repository.findBlendSnapshotByKey(blendKey));
        });
    }

    /** 按掺配业务键查询不可变快照（含明细），不存在返回 404。 */
    public BlendSnapshotResponse getBlendSnapshot(String blendKey) {
        requireKey("blendKey", blendKey);
        BlendSnapshotRow snapshot = repository.findBlendSnapshotByKey(blendKey);
        if (snapshot == null) {
            throw ApiException.notFound("BLEND_NOT_FOUND", "掺配快照不存在: " + blendKey);
        }
        return toBlendSnapshotResponse(snapshot);
    }

    /** 查询申请全部掺配快照（不可变），按发生顺序返回；申请不存在返回 404。 */
    public BlendSnapshotListResponse listBlendSnapshots(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<BlendSnapshotResponse> snapshots = repository.listBlendSnapshots(allocationKey).stream()
                .map(this::toBlendSnapshotResponse).toList();
        return new BlendSnapshotListResponse(allocationKey, snapshots);
    }

    /**
     * 查询申请累计水质：全部掺配快照、累计核销量、剩余额度与按核销量加权的累计平均盐度（mg/L）。
     * 无核销时累计平均盐度为 null。
     */
    public AllocationBlendSummaryResponse getAllocationBlendSummary(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<BlendSnapshotResponse> snapshots = repository.listBlendSnapshots(allocationKey).stream()
                .map(this::toBlendSnapshotResponse).toList();
        BigDecimal writtenTotal = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (BlendSnapshotRow row : repository.listBlendSnapshots(allocationKey)) {
            writtenTotal = writtenTotal.add(row.totalAmount());
            weightedSum = weightedSum.add(row.weightedSalinity().multiply(row.totalAmount()));
        }
        String cumulative = writtenTotal.signum() == 0 ? null
                : fmtSalinity(weightedSum.divide(writtenTotal, SALINITY_SCALE, RoundingMode.HALF_UP));
        return new AllocationBlendSummaryResponse(allocationKey, allocation.status(),
                fmt(allocation.amount()), fmt(allocation.heldAmount()), fmt(writtenTotal),
                fmt(allocation.heldAmount()),
                allocation.salinityLimit() == null ? null : fmt(allocation.salinityLimit()),
                cumulative, snapshots);
    }

    /** 规范化掺配参数串：操作者|申请|申请版本|核销量|按 source_key 升序的 source:amount 列表（换序同参）。 */
    private String blendParams(String actor, String allocationKey, long expectedAllocationVersion,
                               BigDecimal total, TreeMap<String, BigDecimal> normalized) {
        StringBuilder sb = new StringBuilder("BLEND_WRITE_OFF|").append(actor).append('|')
                .append(allocationKey).append('|').append(expectedAllocationVersion).append('|')
                .append(total.toPlainString()).append('|');
        boolean first = true;
        for (Map.Entry<String, BigDecimal> entry : normalized.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append(':').append(entry.getValue().toPlainString());
            first = false;
        }
        return sb.toString();
    }

    /** 加权平均盐度 = Σ(取水量 × 水源盐度) / 总取水量，保留 6 位小数 HALF_UP。 */
    private BigDecimal weightedSalinity(TreeMap<String, BigDecimal> normalized, Map<String, SourceRow> rows,
                                        BigDecimal total) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : normalized.entrySet()) {
            weightedSum = weightedSum.add(entry.getValue().multiply(rows.get(entry.getKey()).salinity()));
        }
        return weightedSum.divide(total, SALINITY_SCALE, RoundingMode.HALF_UP);
    }

    private SourceResponse toSourceResponse(SourceRow row) {
        return new SourceResponse(row.id(), row.sourceKey(), fmt(row.availableAmount()),
                fmt(row.salinity()), row.version(), toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private BlendSnapshotResponse toBlendSnapshotResponse(BlendSnapshotRow row) {
        List<BlendItemResponse> items = repository.listBlendItems(row.id()).stream()
                .map(item -> new BlendItemResponse(item.sourceKey(), fmt(item.amount()),
                        fmt(item.salinity()), item.ordinal()))
                .toList();
        return new BlendSnapshotResponse(row.blendKey(), row.allocationKey(), row.actor(),
                fmt(row.totalAmount()), fmtSalinity(row.weightedSalinity()),
                row.salinityLimit() == null ? null : fmt(row.salinityLimit()),
                row.allocationVersion(), toIso(row.createdNanos()), items);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     */
    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private WindowRow lockWindowOf(AllocationRow allocation) {
        WindowRow window = repository.lockWindowById(allocation.windowId());
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
        }
        return window;
    }

    private BigDecimal availableTotal(WindowRow window) {
        CurtailmentRow active = repository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()),
                row.salinityLimit() == null ? null : fmt(row.salinityLimit()), row.version(),
                row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    static long nowNanos() {
        return toNanos(Instant.now());
    }

    /** Instant -> UTC 纳秒时间戳。 */
    static long toNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), NANOS_PER_SECOND), instant.getNano());
    }

    /** UTC 纳秒时间戳 -> Instant。 */
    static Instant toInstant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, NANOS_PER_SECOND),
                Math.floorMod(nanos, NANOS_PER_SECOND));
    }

    static String toIso(long nanos) {
        return toInstant(nanos).toString();
    }

    static long parseInstant(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        try {
            return toNanos(Instant.parse(value.trim()));
        } catch (DateTimeParseException | ArithmeticException e) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不是合法的 UTC 时刻: " + value);
        }
    }

    /** 解析水量：十进制字符串，大于 0，最多 3 位小数。 */
    static BigDecimal parseAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为大于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须大于 0");
        }
        return amount;
    }

    /** 解析允许为 0 的水量（水源可用量初始值），最多 3 位小数。 */
    static BigDecimal parseNonNegativeAmount(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 必须非负");
        }
        return amount;
    }

    /** 解析盐度（mg/L）：非负，最多 3 位小数。 */
    static BigDecimal parseSalinity(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!SALINITY_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串（毫克每升），最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
    }

    /** 解析可选盐度上限：null/空白返回 null（不限制），否则按盐度校验。 */
    static BigDecimal parseOptionalSalinity(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseSalinity(field, value);
    }

    static void requireKey(String field, String value) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 不能为空，且仅允许字母数字及 . - _ :，最长 128 字符");
        }
    }

    /** 水量格式化为十进制字符串（去掉多余尾零）。 */
    static String fmt(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.signum() == 0) {
            return "0";
        }
        return stripped.toPlainString();
    }

    /** 加权平均盐度格式化：固定保留 6 位小数（计算精度），mg/L。 */
    static String fmtSalinity(BigDecimal value) {
        return value.setScale(SALINITY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("响应反序列化失败", e);
        }
    }
}
