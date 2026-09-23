package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.RebalanceRow;
import com.example.starter.water.WaterRepository.SliceRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WindowSourceRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AllocationVersionResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.ConsumeResponse;
import com.example.starter.water.dto.Dtos.ConsumedCellResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.ExpectedVersionRequest;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.MatrixCellResponse;
import com.example.starter.water.dto.Dtos.RebalanceItemRequest;
import com.example.starter.water.dto.Dtos.RebalanceItemResponse;
import com.example.starter.water.dto.Dtos.RebalanceListResponse;
import com.example.starter.water.dto.Dtos.RebalanceResponse;
import com.example.starter.water.dto.Dtos.SliceListResponse;
import com.example.starter.water.dto.Dtos.SliceResponse;
import com.example.starter.water.dto.Dtos.SourceCapRequest;
import com.example.starter.water.dto.Dtos.SourceCapResponse;
import com.example.starter.water.dto.Dtos.SourceCapSnapshotResponse;
import com.example.starter.water.dto.Dtos.SourceListResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    static final String STATUS_CLOSED = "CLOSED";
    static final String STATUS_PREVIEW = "PREVIEW";
    static final String STATUS_ACTIVATED = "ACTIVATED";

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

    /** 提交配水申请，申请人为 actor。窗口已配置水源时 sourceId 必填且必须是窗口水源。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String sourceId, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + sourceId + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            List<WindowSourceRow> sources = repository.listWindowSources(windowId);
            if (sources.isEmpty()) {
                if (sourceId != null) {
                    throw ApiException.badRequest("SOURCE_NOT_CONFIGURED", "窗口未配置水源，不能绑定 sourceId");
                }
            } else {
                if (sourceId == null) {
                    throw ApiException.badRequest("SOURCE_REQUIRED", "窗口已配置水源，sourceId 必填");
                }
                requireKey("sourceId", sourceId);
                boolean configured = sources.stream().anyMatch(s -> s.sourceId().equals(sourceId));
                if (!configured) {
                    throw ApiException.badRequest("SOURCE_UNKNOWN",
                            "sourceId 不是该窗口已配置的水源: " + sourceId);
                }
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, sourceId,
                    nowNanos());
            if (sourceId != null) {
                repository.insertSlice(id, sourceId, BigDecimal.ZERO, BigDecimal.ZERO);
            }
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
            // 普通批准：初始绑定水源分片额度同步为持有额度
            syncSingleSliceToHeld(allocation.id());
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
            // 已核销用水量不能随取消归零：存在核销量的申请禁止取消
            List<SliceRow> slices = repository.listSlicesByAllocation(allocation.id());
            boolean hasConsumed = slices.stream().anyMatch(s -> s.consumed().signum() > 0);
            if (hasConsumed) {
                throw ApiException.conflict("ALLOCATION_HAS_CONSUMED", "申请存在已核销用水量，不能取消");
            }
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, nowNanos());
            // 取消：持有额度归零，全部分片额度同步归零（已校验无核销量）
            for (SliceRow slice : slices) {
                if (slice.amount().signum() > 0) {
                    repository.updateSlice(slice.id(), BigDecimal.ZERO, slice.consumed());
                }
            }
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
            // 已核销用水量不能被转让走：源分片按 sourceId 顺序扣减，不可低于各自核销量；
            // 窗口未配置水源时无分片，保持原有仅按持有额度扣减的行为
            List<SliceRow> sourceSlices = repository.listSlicesByAllocation(lockedSource.id());
            if (!sourceSlices.isEmpty()) {
                BigDecimal remaining = amount;
                for (SliceRow slice : sourceSlices) {
                    BigDecimal movable = slice.amount().subtract(slice.consumed());
                    BigDecimal take = movable.min(remaining);
                    if (take.signum() > 0) {
                        repository.updateSlice(slice.id(), slice.amount().subtract(take), slice.consumed());
                        remaining = remaining.subtract(take);
                    }
                }
                if (remaining.signum() > 0) {
                    throw ApiException.quotaExceeded("源申请扣除已核销用水量后可转让额度不足，差额 "
                            + fmt(remaining));
                }
            }
            long now = nowNanos();
            repository.decrementHeldAmount(lockedSource.id(), amount, now);
            repository.updateAllocationStatus(lockedTarget.id(), STATUS_APPROVED, now);
            // 目标批准：初始绑定水源分片额度同步为持有额度
            syncSingleSliceToHeld(lockedTarget.id());
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
    // 水源配置与窗口关闭
    // ------------------------------------------------------------------

    /** 配置/整体替换窗口水源（1~10 个，sourceId 不得重复），在窗口锁内先删后插。 */
    public SourceListResponse configureSources(String commandKey, long windowId,
                                               List<SourceCapRequest> sources) {
        requireKey("commandKey", commandKey);
        List<SourceCapInput> parsed = parseSourceCaps(sources);
        String params = "SOURCES_CONFIGURE|" + windowId + "|" + parsed.stream()
                .map(s -> s.sourceId() + "=" + s.cap().toPlainString())
                .collect(Collectors.joining(","));
        return runCommand("SOURCES_CONFIGURE", commandKey, params, SourceListResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            requireWindowOpen(window);
            repository.deleteWindowSources(windowId);
            long now = nowNanos();
            for (SourceCapInput source : parsed) {
                repository.insertWindowSource(windowId, source.sourceId(), source.cap(), now);
            }
            return getSources(windowId);
        });
    }

    /** 查询窗口水源配置，按 sourceId 升序。 */
    public SourceListResponse getSources(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<SourceCapResponse> sources = repository.listWindowSources(windowId).stream()
                .map(s -> new SourceCapResponse(s.sourceId(), fmt(s.supplyCap()))).toList();
        return new SourceListResponse(windowId, sources);
    }

    /** 关闭窗口：关闭后不可重平衡、不可重配水源。 */
    public WindowResponse closeWindow(String commandKey, long windowId) {
        requireKey("commandKey", commandKey);
        String params = "WINDOW_CLOSE|" + windowId;
        return runCommand("WINDOW_CLOSE", commandKey, params, WindowResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (STATUS_CLOSED.equals(window.status())) {
                throw ApiException.conflict("WINDOW_ALREADY_CLOSED", "窗口已关闭，不能重复关闭");
            }
            repository.closeWindow(windowId);
            return toWindowResponse(repository.findWindowById(windowId),
                    repository.findActiveCurtailment(windowId));
        });
    }

    // ------------------------------------------------------------------
    // 用水核销
    // ------------------------------------------------------------------

    /**
     * 用水核销：在申请的指定水源分片上登记已核销用水量，不超过该分片剩余可核销量。
     * 与批准/转让/限供/重平衡一样在窗口锁内串行，成功后申请版本递增。
     */
    public ConsumeResponse consume(String commandKey, String allocationKey, String sourceId, String amount) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("sourceId", sourceId);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "CONSUME|" + allocationKey + "|" + sourceId + "|" + qty.toPlainString();
        return runCommand("CONSUME", commandKey, params, ConsumeResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED", "只有 APPROVED 状态的申请可以核销");
            }
            SliceRow slice = repository.findSlice(allocation.id(), sourceId);
            if (slice == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_BOUND",
                        "申请在水源 " + sourceId + " 上没有额度分片");
            }
            BigDecimal available = slice.amount().subtract(slice.consumed());
            if (qty.compareTo(available) > 0) {
                throw ApiException.quotaExceeded("水源 " + sourceId + " 分片剩余可核销量 "
                        + fmt(available) + " 不足，无法核销 " + fmt(qty));
            }
            BigDecimal newConsumed = slice.consumed().add(qty);
            repository.updateSlice(slice.id(), slice.amount(), newConsumed);
            repository.bumpVersion(allocation.id(), nowNanos());
            return new ConsumeResponse(allocationKey, sourceId, fmt(newConsumed),
                    fmt(available.subtract(qty)), allocation.version() + 1);
        });
    }

    /** 查询申请的水源分片矩阵行，按 sourceId 升序。 */
    public SliceListResponse getSlices(String allocationKey) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SliceResponse> slices = repository.listSlicesByAllocation(allocation.id()).stream()
                .map(s -> new SliceResponse(s.sourceId(), fmt(s.amount()), fmt(s.consumed()))).toList();
        return new SliceListResponse(allocationKey, slices);
    }

    // ------------------------------------------------------------------
    // 多水源配额守恒重平衡
    // ------------------------------------------------------------------

    /**
     * 重平衡预览：只读。规范化重复明细并按完整矩阵计算后态，执行与激活相同的守恒、
     * 已核销量、适用性与供给上限校验，不落库、不改版本。
     */
    public RebalanceResponse previewRebalance(long windowId, List<RebalanceItemRequest> rawItems,
                                              List<ExpectedVersionRequest> rawVersions) {
        List<NormItem> items = normalizeItems(rawItems);
        Map<String, Long> expectedVersions = parseExpectedVersions(rawVersions, involvedKeys(items));
        return tx.execute(status -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            requireWindowOpen(window);
            RebalancePlan plan = computePlan(window, items, expectedVersions);
            return toRebalanceResponse(null, windowId, STATUS_PREVIEW, null, plan, false);
        });
    }

    /**
     * 激活重平衡单：在一个事务内重读窗口、供给上限、全部相关额度、实际核销量与版本，
     * 任一校验失败整单回滚（矩阵不变、requestId 不占键）；成功则一次性更新全部分片、
     * 逐申请增版并冻结规范化明细、前后矩阵、上限与核销量快照。
     */
    public RebalanceResponse activateRebalance(String requestId, String rebalanceKey, long windowId,
                                               List<RebalanceItemRequest> rawItems,
                                               List<ExpectedVersionRequest> rawVersions) {
        requireKey("requestId", requestId);
        requireKey("rebalanceKey", rebalanceKey);
        List<NormItem> items = normalizeItems(rawItems);
        Map<String, Long> expectedVersions = parseExpectedVersions(rawVersions, involvedKeys(items));
        // 幂等同参判定基于规范化后的明细与版本：明细换序、重复拆分均视为同参
        String params = canonicalRebalanceParams(windowId, rebalanceKey, items, expectedVersions);
        return runCommand("REBALANCE_ACTIVATE", requestId, params, RebalanceResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            requireWindowOpen(window);
            if (repository.findRebalanceByKey(rebalanceKey) != null) {
                throw ApiException.conflict("REBALANCE_KEY_REUSED", "rebalanceKey 已被使用: " + rebalanceKey);
            }
            RebalancePlan plan = computePlan(window, items, expectedVersions);
            long now = nowNanos();
            for (SliceUpdate update : plan.updates()) {
                if (update.sliceId() != null) {
                    repository.updateSlice(update.sliceId(), update.amount(), update.consumed());
                } else {
                    repository.insertSlice(update.allocationId(), update.sourceId(), update.amount(),
                            BigDecimal.ZERO);
                }
            }
            for (AllocationRow allocation : plan.involved().values()) {
                repository.bumpVersion(allocation.id(), now);
            }
            RebalanceResponse response = toRebalanceResponse(rebalanceKey, windowId, STATUS_ACTIVATED,
                    toIso(now), plan, true);
            try {
                repository.insertRebalance(rebalanceKey, windowId, requestId, toJson(response), now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 rebalanceKey（换 requestId）：事务回滚，矩阵不变
                throw ApiException.conflict("REBALANCE_KEY_REUSED",
                        "rebalanceKey 已被使用: " + rebalanceKey);
            }
            return response;
        });
    }

    /** 查询重平衡单冻结快照（只读证据，快照内已按 sourceId、区块稳定排序）。 */
    public RebalanceResponse getRebalance(long windowId, String rebalanceKey) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        RebalanceRow row = repository.findRebalanceByKey(rebalanceKey);
        if (row == null || row.windowId() != windowId) {
            throw ApiException.notFound("REBALANCE_NOT_FOUND", "重平衡单不存在: " + rebalanceKey);
        }
        return fromJson(row.snapshotJson(), RebalanceResponse.class);
    }

    /** 查询窗口全部重平衡单，按激活顺序。 */
    public RebalanceListResponse listRebalances(long windowId) {
        if (repository.findWindowById(windowId) == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<RebalanceResponse> rebalances = repository.listRebalances(windowId).stream()
                .map(row -> fromJson(row.snapshotJson(), RebalanceResponse.class)).toList();
        return new RebalanceListResponse(windowId, rebalances);
    }

    // ------------------------------------------------------------------
    // 重平衡内部实现
    // ------------------------------------------------------------------

    /** 规范化后的重平衡明细：同一区块同一源目标的重复明细已求和。 */
    private record NormItem(String allocationKey, String fromSourceId, String toSourceId,
                            BigDecimal amount) {
    }

    /** 水源配置入参（已校验并按 sourceId 排序）。 */
    private record SourceCapInput(String sourceId, BigDecimal cap) {
    }

    /** 分片更新计划：sliceId 为 null 表示新增分片。 */
    private record SliceUpdate(Long sliceId, long allocationId, String sourceId, BigDecimal amount,
                               BigDecimal consumed) {
    }

    /** 重平衡计算计划：校验通过后可直接应用或序列化为快照。 */
    private record RebalancePlan(List<NormItem> items, List<SliceRow> beforeSlices,
                                 Map<String, Map<String, BigDecimal>> afterMatrix,
                                 List<WindowSourceRow> sources, Map<String, AllocationRow> involved,
                                 List<SliceUpdate> updates) {
    }

    /** 解析并校验水源配置：1~10 个、sourceId 合法且不重复、上限为正且最多 3 位小数，按 sourceId 排序。 */
    private List<SourceCapInput> parseSourceCaps(List<SourceCapRequest> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 10) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "水源数量必须为 1~10 个");
        }
        Map<String, BigDecimal> caps = new HashMap<>();
        for (SourceCapRequest source : sources) {
            if (source == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "水源配置项不能为空");
            }
            requireKey("sourceId", source.sourceId());
            BigDecimal cap = parseAmount("supplyCap", source.supplyCap());
            if (caps.putIfAbsent(source.sourceId(), cap) != null) {
                throw ApiException.badRequest("DUPLICATE_SOURCE", "sourceId 重复: " + source.sourceId());
            }
        }
        return caps.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> new SourceCapInput(e.getKey(), e.getValue())).toList();
    }

    /** 规范化重平衡明细：校验 2~50 条、金额与水源合法，同区块同源目标求和，按源水源、区块、目标水源排序。 */
    private List<NormItem> normalizeItems(List<RebalanceItemRequest> rawItems) {
        if (rawItems == null || rawItems.size() < 2 || rawItems.size() > 50) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "重平衡明细条数必须为 2~50");
        }
        Map<String, BigDecimal> sums = new HashMap<>();
        Map<String, NormItem> keys = new HashMap<>();
        for (RebalanceItemRequest raw : rawItems) {
            if (raw == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "重平衡明细不能为空项");
            }
            requireKey("allocationKey", raw.allocationKey());
            requireKey("fromSourceId", raw.fromSourceId());
            requireKey("toSourceId", raw.toSourceId());
            if (raw.fromSourceId().equals(raw.toSourceId())) {
                throw ApiException.badRequest("SAME_SOURCE", "源水源与目标水源不能相同: " + raw.fromSourceId());
            }
            BigDecimal amount = parseAmount("amount", raw.amount());
            String groupKey = raw.allocationKey() + "\n" + raw.fromSourceId() + "\n" + raw.toSourceId();
            keys.putIfAbsent(groupKey,
                    new NormItem(raw.allocationKey(), raw.fromSourceId(), raw.toSourceId(), amount));
            sums.merge(groupKey, amount, BigDecimal::add);
        }
        List<NormItem> items = new ArrayList<>();
        for (Map.Entry<String, NormItem> entry : keys.entrySet()) {
            NormItem item = entry.getValue();
            items.add(new NormItem(item.allocationKey(), item.fromSourceId(), item.toSourceId(),
                    sums.get(entry.getKey())));
        }
        items.sort(Comparator.comparing(NormItem::fromSourceId)
                .thenComparing(NormItem::allocationKey).thenComparing(NormItem::toSourceId));
        return items;
    }

    /** 涉及区块集合（明细中出现过的 allocationKey）。 */
    private Set<String> involvedKeys(List<NormItem> items) {
        Set<String> keys = new TreeSet<>();
        for (NormItem item : items) {
            keys.add(item.allocationKey());
        }
        return keys;
    }

    /** 解析期望版本：必须恰好覆盖涉及区块，版本为非负整数且不重复。 */
    private Map<String, Long> parseExpectedVersions(List<ExpectedVersionRequest> rawVersions,
                                                    Set<String> involved) {
        if (rawVersions == null || rawVersions.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersions 不能为空");
        }
        Map<String, Long> versions = new HashMap<>();
        for (ExpectedVersionRequest raw : rawVersions) {
            if (raw == null || raw.version() == null || raw.version() < 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 必须为非负整数");
            }
            requireKey("allocationKey", raw.allocationKey());
            if (versions.putIfAbsent(raw.allocationKey(), raw.version()) != null) {
                throw ApiException.badRequest("DUPLICATE_VERSION",
                        "expectedVersions 中 allocationKey 重复: " + raw.allocationKey());
            }
        }
        if (!versions.keySet().equals(involved)) {
            throw ApiException.badRequest("VERSION_SET_MISMATCH",
                    "expectedVersions 必须恰好覆盖明细涉及的区块: " + involved);
        }
        return versions;
    }

    /** 幂等参数串：窗口、rebalanceKey、规范化明细（已排序）与期望版本（按键排序）。 */
    private String canonicalRebalanceParams(long windowId, String rebalanceKey, List<NormItem> items,
                                            Map<String, Long> expectedVersions) {
        String itemsPart = items.stream()
                .map(i -> i.allocationKey() + ":" + i.fromSourceId() + ">" + i.toSourceId() + "="
                        + i.amount().stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
        String versionsPart = expectedVersions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "@" + e.getValue())
                .collect(Collectors.joining(","));
        return "REBALANCE_ACTIVATE|" + windowId + "|" + rebalanceKey + "|" + itemsPart + "|" + versionsPart;
    }

    /**
     * 在窗口锁内按完整矩阵计算重平衡后态：重读供给上限、涉及额度、实际核销量与版本，
     * 校验守恒（逐行搬移天然守恒）、已核销量不可搬走、目标水源适用性与各水源供给上限。
     * 先汇总每个源分片的总搬出一次性校验，再统一应用，不因逐条扣减顺序产生临时超限。
     */
    private RebalancePlan computePlan(WindowRow window, List<NormItem> items,
                                      Map<String, Long> expectedVersions) {
        List<WindowSourceRow> sources = repository.listWindowSources(window.id());
        if (sources.isEmpty()) {
            throw ApiException.conflict("SOURCES_NOT_CONFIGURED", "窗口未配置水源，不能重平衡");
        }
        Map<String, BigDecimal> caps = new LinkedHashMap<>();
        for (WindowSourceRow source : sources) {
            caps.put(source.sourceId(), source.supplyCap());
        }
        // 适用性：目标水源必须是窗口当前已配置水源
        for (NormItem item : items) {
            if (!caps.containsKey(item.toSourceId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SOURCE_NOT_APPLICABLE",
                        "目标水源不适用于该区块所在窗口: " + item.toSourceId());
            }
        }
        // 涉及额度：存在性、同窗口、已批准、版本一致
        Map<String, AllocationRow> involved = new LinkedHashMap<>();
        for (String allocationKey : involvedKeys(items)) {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            if (allocation.windowId() != window.id()) {
                throw ApiException.conflict("DIFFERENT_WINDOW", "区块不属于该供水窗口: " + allocationKey);
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的区块可以重平衡: " + allocationKey);
            }
            long expected = expectedVersions.get(allocationKey);
            if (allocation.version() != expected) {
                throw ApiException.conflict("VERSION_CONFLICT", "区块 " + allocationKey
                        + " 版本已变化：期望 " + expected + "，实际 " + allocation.version());
            }
            involved.put(allocationKey, allocation);
        }
        // 完整矩阵：窗口全部申请的全部分片
        List<SliceRow> beforeSlices = repository.listSlicesByWindow(window.id());
        Map<String, Map<String, BigDecimal>> after = new HashMap<>();
        Map<String, Map<String, BigDecimal>> consumed = new HashMap<>();
        for (SliceRow slice : beforeSlices) {
            after.computeIfAbsent(slice.allocationKey(), k -> new HashMap<>())
                    .put(slice.sourceId(), slice.amount());
            consumed.computeIfAbsent(slice.allocationKey(), k -> new HashMap<>())
                    .put(slice.sourceId(), slice.consumed());
        }
        // 配置水源前提交的申请可能没有任何分片：矩阵行按空处理，搬出会因后态余额不足失败
        for (String allocationKey : involved.keySet()) {
            after.computeIfAbsent(allocationKey, k -> new HashMap<>());
        }
        // 统一应用全部搬移到后态矩阵：同一区块行内总额守恒，闭环与链式搬移与明细顺序无关
        for (NormItem item : items) {
            after.get(item.allocationKey()).merge(item.fromSourceId(), item.amount().negate(),
                    BigDecimal::add);
            after.get(item.allocationKey()).computeIfAbsent(item.toSourceId(), k -> BigDecimal.ZERO);
            after.get(item.allocationKey()).merge(item.toSourceId(), item.amount(), BigDecimal::add);
        }
        // 完整后态校验：每个单元不得低于其已核销用水量（已核销部分不能被搬走，余额不足整体失败）
        for (String allocationKey : involved.keySet()) {
            Map<String, BigDecimal> afterCells = after.get(allocationKey);
            Map<String, BigDecimal> consumedCells = consumed.getOrDefault(allocationKey, Map.of());
            for (Map.Entry<String, BigDecimal> cell : afterCells.entrySet()) {
                BigDecimal used = consumedCells.getOrDefault(cell.getKey(), BigDecimal.ZERO);
                if (cell.getValue().compareTo(used) < 0) {
                    throw ApiException.quotaExceeded("区块 " + allocationKey + " 在水源 " + cell.getKey()
                            + " 后态额度 " + fmt(cell.getValue()) + " 低于已核销用水量 " + fmt(used)
                            + "（已核销用水量不能搬走，余额不足）");
                }
            }
        }
        // 供给上限：按完整后态汇总各水源总分配
        Map<String, BigDecimal> afterTotals = totalBySource(after);
        for (WindowSourceRow source : sources) {
            BigDecimal total = afterTotals.getOrDefault(source.sourceId(), BigDecimal.ZERO);
            if (total.compareTo(source.supplyCap()) > 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SUPPLY_CAP_EXCEEDED",
                        "水源 " + source.sourceId() + " 重平衡后总分配 " + fmt(total)
                                + " 超过供给上限 " + fmt(source.supplyCap()));
            }
        }
        // 分片更新计划：仅涉及区块发生变化的单元
        List<SliceUpdate> updates = new ArrayList<>();
        Map<String, SliceRow> sliceByCell = new HashMap<>();
        for (SliceRow slice : beforeSlices) {
            sliceByCell.put(slice.allocationKey() + "\n" + slice.sourceId(), slice);
        }
        for (String allocationKey : involved.keySet()) {
            AllocationRow allocation = involved.get(allocationKey);
            Map<String, BigDecimal> afterCells = after.get(allocationKey);
            Set<String> cellSources = new TreeSet<>(afterCells.keySet());
            for (String sourceId : cellSources) {
                BigDecimal newAmount = afterCells.get(sourceId);
                SliceRow existing = sliceByCell.get(allocationKey + "\n" + sourceId);
                if (existing != null) {
                    if (existing.amount().compareTo(newAmount) != 0) {
                        updates.add(new SliceUpdate(existing.id(), allocation.id(), sourceId, newAmount,
                                existing.consumed()));
                    }
                } else if (newAmount.signum() != 0) {
                    updates.add(new SliceUpdate(null, allocation.id(), sourceId, newAmount,
                            BigDecimal.ZERO));
                }
            }
        }
        return new RebalancePlan(items, beforeSlices, after, sources, involved, updates);
    }

    /** 构建重平衡响应/快照：规范化明细、前后完整矩阵、供给上限与核销量快照、涉及额度版本。 */
    private RebalanceResponse toRebalanceResponse(String rebalanceKey, long windowId, String status,
                                                  String createdUtc, RebalancePlan plan,
                                                  boolean activated) {
        List<RebalanceItemResponse> normalizedItems = plan.items().stream()
                .map(i -> new RebalanceItemResponse(i.allocationKey(), i.fromSourceId(), i.toSourceId(),
                        fmt(i.amount())))
                .toList();
        List<MatrixCellResponse> beforeMatrix = plan.beforeSlices().stream()
                .map(s -> new MatrixCellResponse(s.allocationKey(), s.sourceId(), fmt(s.amount())))
                .toList();
        List<MatrixCellResponse> afterMatrix = new ArrayList<>();
        for (Map.Entry<String, Map<String, BigDecimal>> row : plan.afterMatrix().entrySet()) {
            for (Map.Entry<String, BigDecimal> cell : row.getValue().entrySet()) {
                afterMatrix.add(new MatrixCellResponse(row.getKey(), cell.getKey(), fmt(cell.getValue())));
            }
        }
        afterMatrix.sort(Comparator.comparing(MatrixCellResponse::sourceId)
                .thenComparing(MatrixCellResponse::allocationKey));
        Map<String, BigDecimal> beforeTotals = new HashMap<>();
        for (SliceRow slice : plan.beforeSlices()) {
            beforeTotals.merge(slice.sourceId(), slice.amount(), BigDecimal::add);
        }
        Map<String, BigDecimal> afterTotals = totalBySource(plan.afterMatrix());
        List<SourceCapSnapshotResponse> caps = plan.sources().stream()
                .map(s -> new SourceCapSnapshotResponse(s.sourceId(), fmt(s.supplyCap()),
                        fmt(beforeTotals.getOrDefault(s.sourceId(), BigDecimal.ZERO)),
                        fmt(afterTotals.getOrDefault(s.sourceId(), BigDecimal.ZERO))))
                .toList();
        List<ConsumedCellResponse> consumedSnapshot = plan.beforeSlices().stream()
                .map(s -> new ConsumedCellResponse(s.allocationKey(), s.sourceId(), fmt(s.consumed())))
                .toList();
        List<AllocationVersionResponse> versions = plan.involved().entrySet().stream()
                .map(e -> new AllocationVersionResponse(e.getKey(),
                        e.getValue().version() + (activated ? 1 : 0)))
                .toList();
        return new RebalanceResponse(rebalanceKey, windowId, status, createdUtc, normalizedItems,
                beforeMatrix, afterMatrix, caps, consumedSnapshot, versions);
    }

    /** 按水源汇总矩阵总分配。 */
    private Map<String, BigDecimal> totalBySource(Map<String, Map<String, BigDecimal>> matrix) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (Map<String, BigDecimal> row : matrix.values()) {
            for (Map.Entry<String, BigDecimal> cell : row.entrySet()) {
                totals.merge(cell.getKey(), cell.getValue(), BigDecimal::add);
            }
        }
        return totals;
    }

    /** 窗口必须为 OPEN，否则 409。 */
    private void requireWindowOpen(WindowRow window) {
        if (STATUS_CLOSED.equals(window.status())) {
            throw ApiException.conflict("WINDOW_CLOSED", "窗口已关闭，不能执行该操作: " + window.id());
        }
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

    /**
     * 把申请在其初始绑定水源上的分片额度同步为当前持有额度（普通批准/转让目标批准）。
     * 仅在申请持有单一分片（未经重平衡）时调用；无绑定水源或无分片时不做事。
     */
    private void syncSingleSliceToHeld(long allocationId) {
        List<SliceRow> slices = repository.listSlicesByAllocation(allocationId);
        if (slices.size() != 1) {
            return;
        }
        SliceRow slice = slices.get(0);
        // 调用方刚更新过状态，重新读取保证持有额度一致
        AllocationRow row = repository.findAllocationByKey(slice.allocationKey());
        if (row == null) {
            return;
        }
        if (slice.amount().compareTo(row.heldAmount()) != 0) {
            repository.updateSlice(slice.id(), row.heldAmount(), slice.consumed());
        }
    }

    private BigDecimal availableTotal(WindowRow window) {
        CurtailmentRow active = repository.findActiveCurtailment(window.id());
        return active != null ? active.volume() : window.plannedVolume();
    }

    private WindowResponse toWindowResponse(WindowRow row, CurtailmentRow active) {
        BigDecimal available = active != null ? active.volume() : row.plannedVolume();
        return new WindowResponse(row.id(), row.windowKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), fmt(row.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), row.status(),
                toIso(row.createdNanos()));
    }

    private AllocationResponse toAllocationResponse(AllocationRow row) {
        return new AllocationResponse(row.allocationKey(), row.windowId(), row.userId(), fmt(row.amount()),
                fmt(row.heldAmount()), row.requester(), row.status(), row.sourceId(), row.version(),
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
