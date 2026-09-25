package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.BlendLineRow;
import com.example.starter.water.WaterRepository.BlendSnapshotRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SourceRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AllocationSalinityResponse;
import com.example.starter.water.dto.Dtos.BlendLineResponse;
import com.example.starter.water.dto.Dtos.BlendResponse;
import com.example.starter.water.dto.Dtos.BlendSourceItem;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SourceResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
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
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

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

    /** 提交配水申请，申请人为 actor；maxSalinity 为可空的掺配盐度上限（毫克每升）。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String maxSalinity,
                                               String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        BigDecimal maxSalinityMgL = parseSalinity("maxSalinityMgPerL", maxSalinity);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + (maxSalinityMgL == null ? "-" : maxSalinityMgL.toPlainString())
                + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, maxSalinityMgL,
                    actor, nowNanos());
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
    // 水源与掺配核销
    // ------------------------------------------------------------------

    /** 创建水源：sourceId 全局唯一，可用量与盐度最多 3 位小数。 */
    public SourceResponse createSource(String commandKey, String sourceId, String availableAmount,
                                       String salinity) {
        requireKey("commandKey", commandKey);
        requireKey("sourceId", sourceId);
        BigDecimal available = parseAmount("availableAmount", availableAmount);
        BigDecimal salinityMgL = parseSalinity("salinityMgPerL", salinity);
        if (salinityMgL == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "salinityMgPerL 不能为空");
        }
        String params = "SOURCE_CREATE|" + sourceId + "|" + available.toPlainString() + "|"
                + salinityMgL.toPlainString();
        return runCommand("SOURCE_CREATE", commandKey, params, SourceResponse.class, () -> {
            if (repository.findSourceById(sourceId) != null) {
                throw ApiException.conflict("SOURCE_EXISTS", "水源已存在: " + sourceId);
            }
            try {
                repository.insertSource(sourceId, available, salinityMgL, nowNanos());
            } catch (DuplicateKeyException e) {
                // 并发同 sourceId 创建：事务回滚，按提交顺序后者失败
                throw ApiException.conflict("SOURCE_EXISTS", "水源已存在: " + sourceId);
            }
            return toSourceResponse(repository.findSourceById(sourceId));
        });
    }

    /** 修改水源盐度：expectedVersion 须等于当前版本，否则 409；修改只影响后续核销，不改写历史快照。 */
    public SourceResponse updateSourceSalinity(String commandKey, String sourceId, Long expectedVersion,
                                               String salinity) {
        requireKey("commandKey", commandKey);
        requireKey("sourceId", sourceId);
        if (expectedVersion == null || expectedVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为不小于 0 的整数");
        }
        BigDecimal salinityMgL = parseSalinity("salinityMgPerL", salinity);
        if (salinityMgL == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "salinityMgPerL 不能为空");
        }
        String params = "SOURCE_SALINITY_UPDATE|" + sourceId + "|" + expectedVersion + "|"
                + salinityMgL.toPlainString();
        return runCommand("SOURCE_SALINITY_UPDATE", commandKey, params, SourceResponse.class, () -> {
            SourceRow source = repository.lockSourceById(sourceId);
            if (source == null) {
                throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + sourceId);
            }
            if (source.version() != expectedVersion) {
                throw ApiException.conflict("SOURCE_VERSION_CONFLICT",
                        "水源版本已变更：期望 " + expectedVersion + "，当前 " + source.version());
            }
            repository.updateSourceSalinity(source.id(), salinityMgL, nowNanos());
            return toSourceResponse(repository.findSourceById(sourceId));
        });
    }

    /** 查询水源余量、盐度与版本。 */
    public SourceResponse getSource(String sourceId) {
        SourceRow source = repository.findSourceById(sourceId);
        if (source == null) {
            throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + sourceId);
        }
        return toSourceResponse(source);
    }

    /**
     * 掺配核销：一次核销选择 1 至 5 个水源及各自取水量（换序视为同参），各取水量之和必须等于
     * 申请核销量 settleAmount；任一水源可用量不足、总量不等或加权平均盐度高于申请上限均返回 422
     * 并给出水源余量或计算值。成功时在同一事务扣减全部水源可用量与申请剩余额度，写入不可变掺配
     * 快照；任一扣减失败整单回滚。blendKey 指纹含操作者、申请版本、规范化水源集合与数量，
     * 同键同参重放首次完整结果，失败不占键。
     */
    public BlendResponse blend(String blendKey, String allocationKey, Long allocationVersion,
                               String settleAmount, List<BlendSourceItem> sources, String operator) {
        requireKey("blendKey", blendKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", operator);
        if (allocationVersion == null || allocationVersion < 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "allocationVersion 必须为不小于 0 的整数");
        }
        BigDecimal settle = parseAmount("settleAmount", settleAmount);
        if (sources == null || sources.isEmpty() || sources.size() > 5) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "sources 必须包含 1 至 5 个水源");
        }
        // 规范化：按 sourceId 排序的取水映射，重复水源拒绝
        Map<String, BigDecimal> normalized = new TreeMap<>();
        for (BlendSourceItem item : sources) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "sources 不能包含空项");
            }
            requireKey("sources[].sourceId", item.sourceId());
            BigDecimal amount = parseAmount("sources[" + item.sourceId() + "].amount", item.amount());
            if (normalized.putIfAbsent(item.sourceId(), amount) != null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "水源重复: " + item.sourceId());
            }
        }
        BigDecimal total = normalized.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(settle) != 0) {
            throw ApiException.unprocessable("BLEND_TOTAL_MISMATCH",
                    "各水源取水量之和 " + fmt(total) + " 不等于申请核销量 " + fmt(settle));
        }
        StringBuilder fingerprint = new StringBuilder("BLEND|").append(allocationKey).append('|')
                .append(operator).append('|').append(allocationVersion).append('|')
                .append(settle.toPlainString());
        normalized.forEach((sourceId, amount) -> fingerprint.append('|').append(sourceId).append(':')
                .append(amount.toPlainString()));
        return runCommand("BLEND", blendKey, fingerprint.toString(), BlendResponse.class, () -> {
            // 先锁申请行，与批准/取消/转让按提交顺序串行裁决
            AllocationRow allocation = repository.lockAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            if (allocation.version() != allocationVersion) {
                throw ApiException.conflict("ALLOCATION_VERSION_CONFLICT",
                        "申请版本已变更：期望 " + allocationVersion + "，当前 " + allocation.version());
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + allocation.status());
            }
            if (allocation.heldAmount().compareTo(settle) < 0) {
                throw ApiException.unprocessable("ALLOCATION_BALANCE_INSUFFICIENT",
                        "申请剩余额度 " + fmt(allocation.heldAmount()) + " 不足，无法核销 " + fmt(settle));
            }
            // 再按 sourceId 字典序锁定全部水源行，避免并发核销死锁
            List<SourceRow> locked = new ArrayList<>();
            for (String sourceId : normalized.keySet()) {
                SourceRow source = repository.lockSourceById(sourceId);
                if (source == null) {
                    throw ApiException.notFound("SOURCE_NOT_FOUND", "水源不存在: " + sourceId);
                }
                locked.add(source);
            }
            BigDecimal saltTotal = BigDecimal.ZERO;
            for (SourceRow source : locked) {
                BigDecimal amount = normalized.get(source.sourceId());
                if (source.availableAmount().compareTo(amount) < 0) {
                    throw ApiException.unprocessable("SOURCE_INSUFFICIENT",
                            "水源 " + source.sourceId() + " 余量 " + fmt(source.availableAmount())
                                    + " 不足，需取水 " + fmt(amount));
                }
                saltTotal = saltTotal.add(amount.multiply(source.salinityMgL()));
            }
            // 加权平均盐度 = saltTotal / settle；与上限比较用等价的精确乘法，避免除法舍入误差
            if (allocation.maxSalinityMgL() != null
                    && saltTotal.compareTo(allocation.maxSalinityMgL().multiply(settle)) > 0) {
                BigDecimal average = saltTotal.divide(settle, 6, RoundingMode.HALF_UP);
                throw ApiException.unprocessable("SALINITY_EXCEEDED",
                        "加权平均盐度 " + fmt(average) + " 毫克每升，高于申请上限 "
                                + fmt(allocation.maxSalinityMgL()));
            }
            long now = nowNanos();
            for (SourceRow source : locked) {
                BigDecimal amount = normalized.get(source.sourceId());
                if (repository.deductSourceAvailable(source.id(), amount, now) == 0) {
                    // 行锁内不会发生；兜底保证任一扣减失败整单回滚
                    throw ApiException.unprocessable("SOURCE_INSUFFICIENT",
                            "水源 " + source.sourceId() + " 余量不足，无法扣减 " + fmt(amount));
                }
            }
            if (repository.deductAllocationHeld(allocation.id(), settle, now) == 0) {
                throw ApiException.unprocessable("ALLOCATION_BALANCE_INSUFFICIENT",
                        "申请剩余额度不足，无法核销 " + fmt(settle));
            }
            BigDecimal weighted = saltTotal.divide(settle, 6, RoundingMode.HALF_UP);
            long snapshotId = repository.insertBlendSnapshot(blendKey, allocationKey, allocationVersion,
                    operator, settle, saltTotal, weighted, now);
            for (SourceRow source : locked) {
                repository.insertBlendLine(snapshotId, source.sourceId(), source.version(),
                        normalized.get(source.sourceId()), source.salinityMgL());
            }
            return toBlendResponse(repository.findSnapshotByBlendKey(blendKey));
        });
    }

    /** 查询掺配快照（不可变），含冻结的水源明细。 */
    public BlendResponse getBlendSnapshot(String blendKey) {
        BlendSnapshotRow snapshot = repository.findSnapshotByBlendKey(blendKey);
        if (snapshot == null) {
            throw ApiException.notFound("BLEND_NOT_FOUND", "掺配快照不存在: " + blendKey);
        }
        return toBlendResponse(snapshot);
    }

    /** 查询申请累计盐度：全部核销按核销量加权的累计平均盐度；无核销记录时为 null。 */
    public AllocationSalinityResponse getAllocationSalinity(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<BlendSnapshotRow> snapshots = repository.listSnapshotsByAllocation(allocationKey);
        BigDecimal settledTotal = BigDecimal.ZERO;
        BigDecimal saltTotal = BigDecimal.ZERO;
        for (BlendSnapshotRow snapshot : snapshots) {
            settledTotal = settledTotal.add(snapshot.settleAmount());
            saltTotal = saltTotal.add(snapshot.saltTotal());
        }
        String cumulative = settledTotal.signum() > 0
                ? fmt(saltTotal.divide(settledTotal, 6, RoundingMode.HALF_UP)) : null;
        return new AllocationSalinityResponse(allocationKey, fmt(settledTotal), cumulative,
                snapshots.size());
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
                fmt(row.heldAmount()), row.maxSalinityMgL() == null ? null : fmt(row.maxSalinityMgL()),
                row.version(), row.requester(), row.status(),
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

    private SourceResponse toSourceResponse(SourceRow row) {
        return new SourceResponse(row.sourceId(), fmt(row.availableAmount()), fmt(row.salinityMgL()),
                row.version(), toIso(row.createdNanos()), toIso(row.updatedNanos()));
    }

    private BlendResponse toBlendResponse(BlendSnapshotRow row) {
        List<BlendLineResponse> lines = repository.listBlendLines(row.id()).stream()
                .map(line -> new BlendLineResponse(line.sourceId(), line.sourceVersion(), fmt(line.amount()),
                        fmt(line.salinityMgL())))
                .toList();
        return new BlendResponse(row.blendKey(), row.allocationKey(), row.allocationVersion(),
                row.operator(), fmt(row.settleAmount()), fmt(row.weightedSalinityMgL()), lines,
                toIso(row.createdNanos()));
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

    /** 解析盐度：十进制字符串，不小于 0，最多 3 位小数；空值返回 null（表示未声明）。 */
    static BigDecimal parseSalinity(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为不小于 0 的十进制字符串，最多 3 位小数: " + value);
        }
        return new BigDecimal(trimmed);
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
