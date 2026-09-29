package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.RainConfirmationRow;
import com.example.starter.water.WaterRepository.RainRecordRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteoffItemRow;
import com.example.starter.water.WaterRepository.WriteoffRow;
import com.example.starter.water.dto.Dtos.AllocationDiagnostic;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.AppliedRainView;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.ErrorDetail;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RainConfirmationView;
import com.example.starter.water.dto.Dtos.RainDiagnosticsResponse;
import com.example.starter.water.dto.Dtos.RainListResponse;
import com.example.starter.water.dto.Dtos.RainRecordResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteoffItemRequest;
import com.example.starter.water.dto.Dtos.WriteoffItemResponse;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
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

    static final String RAIN_PENDING = "PENDING";
    static final String RAIN_EFFECTIVE = "EFFECTIVE";
    static final String RAIN_REVOKED = "REVOKED";

    /** 降雨记录生效所需的不同水务角色确认人数。 */
    static final int REQUIRED_RAIN_CONFIRMATIONS = 2;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern SIGNED_DECIMAL3_PATTERN = Pattern.compile("-?\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern SIGNED_DECIMAL6_PATTERN = Pattern.compile("-?\\d{1,16}(\\.\\d{1,6})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Supplier<Instant> clock;

    @org.springframework.beans.factory.annotation.Autowired
    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this(repository, transactionManager, objectMapper, Instant::now);
    }

    WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                 ObjectMapper objectMapper, Supplier<Instant> clock) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
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
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanosTicks());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor；creditCoefficientText 为 null/空白表示不参与降雨抵扣，不允许为负。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String creditCoefficientText,
                                               String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        BigDecimal coefficient = parseOptionalNonNegativeDecimal("creditCoefficient",
                creditCoefficientText, 6);
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + (coefficient == null ? "" : coefficient.toPlainString()) + "|"
                + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, coefficient,
                    nowNanosTicks());
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
            repository.updateAllocationStatus(allocation.id(), STATUS_APPROVED, nowNanosTicks());
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
            repository.updateAllocationStatus(allocation.id(), STATUS_CANCELLED, nowNanosTicks());
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
            long now = nowNanosTicks();
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
            long id = repository.insertCurtailment(windowId, qty, nowNanosTicks());
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
            repository.cancelCurtailment(active.id(), nowNanosTicks());
            List<CurtailmentRow> all = repository.listCurtailments(windowId);
            return all.stream().filter(c -> c.id() == active.id()).findFirst()
                    .map(this::toCurtailmentResponse).orElseThrow();
        });
    }

    // ------------------------------------------------------------------
    // 降雨登记、确认与撤销
    // ------------------------------------------------------------------

    /**
     * 登记窗口降雨记录（初始 PENDING）。降雨量必须大于 0；观测时刻按左闭右开落在窗口内；
     * 同窗口不允许存在另一条 EFFECTIVE 记录（注册阶段即拒绝重复有效记录）。
     */
    public RainRecordResponse registerRain(String commandKey, String rainKey, long windowId,
                                           String rainMillisText, String observedUtc, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal rainMillis = parseRainMillis("rainMillis", rainMillisText);
        long observedNanos = parseInstant("observedUtc", observedUtc);
        String params = "RAIN_REGISTER|" + rainKey + "|" + windowId + "|" + rainMillis.toPlainString()
                + "|" + observedNanos + "|" + actor;
        return runCommand("RAIN_REGISTER", commandKey, params, RainRecordResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (observedNanos < window.startNanos() || observedNanos >= window.endNanos()) {
                throw ApiException.unprocessable("RAIN_OUTSIDE_WINDOW",
                        "降雨观测时刻必须落在窗口 [" + toIso(window.startNanos()) + ","
                                + toIso(window.endNanos()) + ") 内（左闭右开，UTC）");
            }
            if (repository.existsEffectiveRain(windowId)) {
                throw ApiException.unprocessable("RAIN_ALREADY_EFFECTIVE",
                        "同一窗口已存在生效降雨记录，不允许重复有效记录");
            }
            repository.insertRainRecord(rainKey, windowId, rainMillis, observedNanos, actor, nowNanosTicks());
            return toRainResponse(repository.findRainByKey(rainKey));
        });
    }

    /**
     * 水务角色确认降雨：同一操作人不能重复确认；两名不同操作人确认后记录 EFFECTIVE。
     * 生效瞬间若同窗口已存在另一条 EFFECTIVE 记录（并发登记/确认），返回 422 并整体回滚。
     */
    public RainRecordResponse confirmRain(String commandKey, String rainKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("X-Actor-Id", actor);
        String params = "RAIN_CONFIRM|" + rainKey + "|" + actor;
        return runCommand("RAIN_CONFIRM", commandKey, params, RainRecordResponse.class, () -> {
            RainRecordRow rain = repository.findRainByKey(rainKey);
            if (rain == null) {
                throw ApiException.notFound("RAIN_NOT_FOUND", "降雨记录不存在: " + rainKey);
            }
            // 锁窗口，使降雨生效与登记/其他记录确认按提交顺序串行裁决
            WindowRow window = repository.lockWindowById(rain.windowId());
            rain = repository.lockRainByKey(rainKey);
            switch (rain.status()) {
                case RAIN_EFFECTIVE ->
                        throw ApiException.conflict("RAIN_ALREADY_EFFECTIVE_STATE", "降雨记录已生效，无需重复确认");
                case RAIN_REVOKED ->
                        throw ApiException.conflict("RAIN_REVOKED", "已撤销的降雨记录不能确认");
                default -> {
                }
            }
            boolean alreadyConfirmed = repository.listRainConfirmations(rain.id()).stream()
                    .anyMatch(c -> c.confirmer().equals(actor));
            if (alreadyConfirmed) {
                throw ApiException.conflict("RAIN_ALREADY_CONFIRMED_BY_ACTOR",
                        "同一水务角色不能重复确认: " + actor);
            }
            repository.insertRainConfirmation(rain.id(), actor, nowNanosTicks());
            if (repository.countRainConfirmations(rain.id()) >= REQUIRED_RAIN_CONFIRMATIONS) {
                if (repository.existsEffectiveRain(rain.windowId())) {
                    throw ApiException.unprocessable("RAIN_ALREADY_EFFECTIVE",
                            "同一窗口已存在生效降雨记录，本记录不能再生效");
                }
                try {
                    repository.markRainEffective(rain.id(), nowNanosTicks());
                } catch (DuplicateKeyException e) {
                    // 并发下同窗口 EFFECTIVE 唯一约束兜底：事务回滚，不留半成品
                    throw ApiException.unprocessable("RAIN_ALREADY_EFFECTIVE",
                            "同一窗口已存在生效降雨记录，本记录不能再生效");
                }
            }
            return toRainResponse(repository.findRainByKey(rainKey));
        });
    }

    /**
     * 撤销降雨记录：仅 EFFECTIVE/PENDING 可撤销。已结算核销在明细中固化了当时的降雨证据、
     * 系数、抵扣与配额扣减，因此撤销不改写任何已结算核销，只影响后续新核销。
     */
    public RainRecordResponse revokeRain(String commandKey, String rainKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("X-Actor-Id", actor);
        String params = "RAIN_REVOKE|" + rainKey + "|" + actor;
        return runCommand("RAIN_REVOKE", commandKey, params, RainRecordResponse.class, () -> {
            RainRecordRow rain = repository.findRainByKey(rainKey);
            if (rain == null) {
                throw ApiException.notFound("RAIN_NOT_FOUND", "降雨记录不存在: " + rainKey);
            }
            repository.lockWindowById(rain.windowId());
            rain = repository.lockRainByKey(rainKey);
            if (RAIN_REVOKED.equals(rain.status())) {
                throw ApiException.conflict("RAIN_ALREADY_REVOKED", "降雨记录已撤销，不能重复撤销");
            }
            repository.markRainRevoked(rain.id(), nowNanosTicks(), actor);
            return toRainResponse(repository.findRainByKey(rainKey));
        });
    }

    /** 查询窗口全部降雨记录（含确认证据），按登记先后稳定排序。 */
    public RainListResponse getRainRecords(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return new RainListResponse(windowId, repository.listRainRecords(windowId).stream()
                .map(this::toRainResponse).toList());
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    /**
     * 批量核销：所有明细先按当前 EFFECTIVE 降雨与申请系数计算抵扣，再校验每笔最终配额余额；
     * 任一申请非 APPROVED、重复、金额非法或最终持有额度不足，则整批 422 且不写入任何对象。
     * 全部校验通过后，在同一事务内写核销单、明细快照并逐笔扣减持有额度。
     */
    public WriteoffResponse writeoffBatch(String commandKey, String writeoffKey, Long windowId,
                                          List<WriteoffItemRequest> itemRequests, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("writeoffKey", writeoffKey);
        requireKey("X-Actor-Id", actor);
        if (itemRequests == null || itemRequests.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空，至少包含一笔核销明细");
        }
        List<ParsedItem> parsed = new ArrayList<>();
        List<ErrorDetail> shapeErrors = new ArrayList<>();
        for (int i = 0; i < itemRequests.size(); i++) {
            WriteoffItemRequest item = itemRequests.get(i);
            String allocationKey = item == null ? null : item.allocationKey();
            try {
                if (item == null || allocationKey == null || allocationKey.isBlank()) {
                    throw ApiException.badRequest("INVALID_ARGUMENT", "allocationKey 不能为空");
                }
                requireKey("allocationKey", allocationKey);
                BigDecimal amount = parseAmount("amount", item.amount());
                parsed.add(new ParsedItem(i, allocationKey, amount));
            } catch (ApiException e) {
                shapeErrors.add(new ErrorDetail(i, allocationKey, e.code(), e.getMessage(),
                        null, null, null));
            }
        }
        if (!shapeErrors.isEmpty()) {
            throw ApiException.unprocessable("WRITEOFF_INVALID_ITEMS", "核销明细参数非法，整批拒绝",
                    shapeErrors);
        }
        // 幂等指纹：操作者 + 操作类型 + 规范化对象集合（JSON 数组，字段名/顺序固定，
        // 不使用未转义分隔符拼接）+ 所有影响结果的字段
        List<Map<String, Object>> normalizedItems = new ArrayList<>();
        for (ParsedItem p : parsed) {
            Map<String, Object> element = new LinkedHashMap<>();
            element.put("seq", p.seq);
            element.put("allocationKey", p.allocationKey);
            element.put("amount", p.amount.toPlainString());
            normalizedItems.add(element);
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("writeoffKey", writeoffKey);
        fingerprint.put("windowId", windowId);
        fingerprint.put("items", normalizedItems);
        fingerprint.put("actor", actor);
        String params = "WRITEOFF|" + toJson(fingerprint);
        return runCommand("WRITEOFF", commandKey, params, WriteoffResponse.class, () ->
                settleWriteoff(writeoffKey, windowId, parsed, actor));
    }

    /** 在命令事务内完成最终状态校验与多对象提交。 */
    private WriteoffResponse settleWriteoff(String writeoffKey, Long requestedWindowId, List<ParsedItem> parsed,
                                            String actor) {
        if (repository.findWriteoffByKey(writeoffKey) != null) {
            throw ApiException.conflict("WRITEOFF_KEY_REUSED", "writeoffKey 已被使用: " + writeoffKey);
        }
        ParsedItem first = parsed.get(0);
        AllocationRow firstAllocation = repository.findAllocationByKey(first.allocationKey);
        if (firstAllocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                    "配水申请不存在: " + first.allocationKey);
        }
        long windowId = firstAllocation.windowId();
        if (requestedWindowId != null && requestedWindowId != windowId) {
            throw ApiException.conflict("WRITEOFF_WINDOW_MISMATCH",
                    "核销明细不属于路径指定窗口: " + requestedWindowId);
        }
        // 锁窗口行：与批准/转让/取消/降雨确认/撤销按提交顺序串行裁决
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        List<RainRecordRow> effectiveRains = repository.listEffectiveRain(windowId);
        BigDecimal effectiveRainMillis = sumRainMillis(effectiveRains);

        // 重复明细检测：同一申请出现多次时按出现顺序累计本批配额需求
        List<ItemComputation> computations = new ArrayList<>();
        List<ErrorDetail> errors = new ArrayList<>();
        for (ParsedItem parsedItem : parsed) {
            AllocationRow allocation = repository.lockAllocationByKey(parsedItem.allocationKey);
            if (allocation == null) {
                errors.add(new ErrorDetail(parsedItem.seq, parsedItem.allocationKey, "ALLOCATION_NOT_FOUND",
                        "配水申请不存在: " + parsedItem.allocationKey, null, null, null));
                continue;
            }
            if (allocation.windowId() != windowId) {
                errors.add(new ErrorDetail(parsedItem.seq, parsedItem.allocationKey, "DIFFERENT_WINDOW",
                        "核销申请必须与其他明细属于同一供水窗口", null, null, null));
                continue;
            }
            if (!STATUS_APPROVED.equals(allocation.status())) {
                errors.add(new ErrorDetail(parsedItem.seq, parsedItem.allocationKey, "ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销，当前状态: " + allocation.status(),
                        fmt(parsedItem.amount), fmt(allocation.heldAmount()),
                        fmt(parsedItem.amount)));
                continue;
            }
            CreditCalc calc = computeCredit(allocation, effectiveRains, effectiveRainMillis, parsedItem.amount);
            computations.add(new ItemComputation(parsedItem.seq, allocation, parsedItem.amount,
                    calc.credit, calc.quotaDeduction, calc.applied));
        }
        // 同申请多笔的累计配额需求（抵扣独立按各笔 amount 计算）
        Map<String, BigDecimal> accumulatedQuota = new LinkedHashMap<>();
        for (ItemComputation c : computations) {
            accumulatedQuota.merge(c.allocation.allocationKey(), c.quotaDeduction, BigDecimal::add);
        }
        for (ItemComputation c : computations) {
            String key = c.allocation.allocationKey();
            BigDecimal requiredQuota = accumulatedQuota.get(key);
            BigDecimal held = c.allocation.heldAmount();
            if (held.compareTo(requiredQuota) < 0) {
                errors.add(new ErrorDetail(c.seq, key, "QUOTA_EXCEEDED",
                        "本批核销配额扣减需求超过当前持有额度（降雨抵扣不计入配额扣减）",
                        fmt(requiredQuota), fmt(held), fmt(requiredQuota.subtract(held))));
            }
            if (c.amount.compareTo(c.allocation.amount()) > 0) {
                errors.add(new ErrorDetail(c.seq, key, "WRITEOFF_EXCEEDS_ORIGINAL_AMOUNT",
                        "核销原量不能超过申请原水量", fmt(c.amount), fmt(c.allocation.amount()),
                        fmt(c.amount.subtract(c.allocation.amount()))));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.unprocessable("WRITEOFF_REJECTED",
                    "批量核销最终状态校验失败，整批回滚，未写入任何对象", errors);
        }

        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal totalQuota = BigDecimal.ZERO;
        for (ItemComputation c : computations) {
            // 守恒断言：credit <= amount，credit + quota = amount
            if (c.credit.signum() < 0 || c.credit.compareTo(c.amount) > 0
                    || c.credit.add(c.quotaDeduction).compareTo(c.amount) != 0) {
                throw new IllegalStateException("核销抵扣守恒计算失败");
            }
            totalAmount = totalAmount.add(c.amount);
            totalCredit = totalCredit.add(c.credit);
            totalQuota = totalQuota.add(c.quotaDeduction);
        }
        long now = nowNanosTicks();
        long writeoffId = repository.insertWriteoff(writeoffKey, windowId, totalAmount, totalCredit,
                totalQuota, actor, now);
        for (ItemComputation c : computations) {
            repository.insertWriteoffItem(writeoffId, c.seq, c.allocation.allocationKey(), c.amount,
                    c.credit, c.quotaDeduction, c.allocation.creditCoefficient(), toJson(c.applied));
            if (c.quotaDeduction.signum() > 0) {
                boolean updated = repository.deductHeldAmountIfSufficient(c.allocation.id(),
                        c.quotaDeduction, now);
                if (!updated) {
                    // 行锁内最终兜底，理论不可达；失败则整体回滚
                    throw ApiException.quotaExceeded("最终配额扣减失败，持有额度不足，整批回滚");
                }
            }
        }
        return toWriteoffResponse(repository.findWriteoffByKey(writeoffKey));
    }

    /** 查询单笔核销单（含不可变明细快照），按 seq 稳定排序。 */
    public WriteoffResponse getWriteoff(String writeoffKey) {
        WriteoffRow row = repository.findWriteoffByKey(writeoffKey);
        if (row == null) {
            throw ApiException.notFound("WRITEOFF_NOT_FOUND", "核销单不存在: " + writeoffKey);
        }
        return toWriteoffResponse(row);
    }

    /** 窗口降雨与核销诊断：容量、生效降雨、各申请按当前持有额度核销的抵扣预览与差额。 */
    public RainDiagnosticsResponse getRainDiagnostics(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        BigDecimal available = active != null ? active.volume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        List<RainRecordRow> effectiveRains = repository.listEffectiveRain(windowId);
        BigDecimal effectiveRainMillis = sumRainMillis(effectiveRains);
        List<AllocationDiagnostic> diagnostics = repository.listAllocations(windowId).stream()
                .map(a -> {
                    CreditCalc calc = STATUS_APPROVED.equals(a.status())
                            ? computeCredit(a, effectiveRains, effectiveRainMillis, a.heldAmount())
                            : new CreditCalc(BigDecimal.ZERO, a.heldAmount(), List.of());
                    BigDecimal shortfall = calc.quotaDeduction.subtract(a.heldAmount()).max(BigDecimal.ZERO);
                    return new AllocationDiagnostic(a.allocationKey(), a.userId(), a.status(),
                            fmt(a.heldAmount()),
                            a.creditCoefficient() == null ? null : fmt6(a.creditCoefficient()),
                            fmt3(effectiveRainMillis), fmt(calc.credit), fmt(calc.quotaDeduction),
                            fmt(shortfall));
                }).toList();
        return new RainDiagnosticsResponse(windowId, fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), fmt(approved),
                fmt(available.subtract(approved)), fmt3(effectiveRainMillis),
                effectiveRains.stream().map(this::toRainResponse).toList(), diagnostics);
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

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供 + 全部降雨记录 + 全部核销单。 */
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
        List<RainRecordResponse> rains = repository.listRainRecords(windowId).stream()
                .map(this::toRainResponse).toList();
        List<WriteoffResponse> writeoffs = repository.listWriteoffs(windowId).stream()
                .map(this::toWriteoffResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments,
                rains, writeoffs);
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
                repository.insertCommand(commandKey, operation, params, nowNanosTicks());
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
                fmt(row.heldAmount()), row.requester(), row.status(),
                toIso(row.createdNanos()), toIso(row.updatedNanos()),
                row.creditCoefficient() == null ? null : fmt6(row.creditCoefficient()));
    }

    private TransferResponse toTransferResponse(TransferRow row) {
        return new TransferResponse(row.transferKey(), row.windowId(), row.sourceAllocationKey(),
                row.targetAllocationKey(), fmt(row.amount()), row.actor(), toIso(row.createdNanos()));
    }

    private CurtailmentResponse toCurtailmentResponse(CurtailmentRow row) {
        return new CurtailmentResponse(row.id(), row.windowId(), fmt(row.volume()), row.status(),
                toIso(row.createdNanos()), row.cancelledNanos() == null ? null : toIso(row.cancelledNanos()));
    }

    private RainRecordResponse toRainResponse(RainRecordRow row) {
        List<RainConfirmationView> confirmations = repository.listRainConfirmations(row.id()).stream()
                .map(c -> new RainConfirmationView(c.confirmer(), toIso(c.createdNanos())))
                .toList();
        return new RainRecordResponse(row.rainKey(), row.windowId(), fmt(row.rainMillis()),
                toIso(row.observedNanos()), row.status(), row.registeredBy(), toIso(row.createdNanos()),
                row.effectiveNanos() == null ? null : toIso(row.effectiveNanos()),
                row.revokedNanos() == null ? null : toIso(row.revokedNanos()), row.revokedBy(),
                confirmations, REQUIRED_RAIN_CONFIRMATIONS);
    }

    private WriteoffResponse toWriteoffResponse(WriteoffRow row) {
        List<WriteoffItemResponse> items = repository.listWriteoffItems(row.id()).stream()
                .map(this::toWriteoffItemResponse).toList();
        return new WriteoffResponse(row.writeoffKey(), row.windowId(), fmt(row.totalAmount()),
                fmt(row.totalCredit()), fmt(row.totalQuota()), row.status(), row.actor(),
                toIso(row.createdNanos()), items);
    }

    private WriteoffItemResponse toWriteoffItemResponse(WriteoffItemRow row) {
        List<AppliedRainView> applied = fromJsonList(row.appliedRainKeys());
        return new WriteoffItemResponse(row.seqNo(), row.allocationKey(), fmt(row.amount()),
                fmt(row.credit()), fmt(row.quotaDeduction()),
                row.coefficient() == null ? null : fmt(row.coefficient()), applied);
    }

    private List<AppliedRainView> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<AppliedRainView>>() {
                    });
        } catch (Exception e) {
            throw new IllegalStateException("降雨证据快照反序列化失败", e);
        }
    }

    /** 解析后的核销明细请求；seq 为请求中的下标（从 0 起）。 */
    private record ParsedItem(int seq, String allocationKey, BigDecimal amount) {
    }

    /** 已完成抵扣计算、待提交的核销明细。 */
    private record ItemComputation(int seq, AllocationRow allocation, BigDecimal amount, BigDecimal credit,
                                   BigDecimal quotaDeduction, List<AppliedRainView> applied) {
    }

    /** 单笔抵扣纯计算结果。 */
    record CreditCalc(BigDecimal credit, BigDecimal quotaDeduction, List<AppliedRainView> applied) {
    }

    /** 汇总窗口生效降雨量（毫米），无记录为 0。 */
    private static BigDecimal sumRainMillis(List<RainRecordRow> effectiveRains) {
        return effectiveRains.stream().map(RainRecordRow::rainMillis).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 纯计算：按申请的每毫米抵扣系数与窗口当前生效降雨，计算本次核销 amount 的降雨抵扣。
     * 系数为 null（不参与）或无生效降雨时抵扣为 0；抵扣总量按 amount 封顶（credit <= amount），
     * 配额扣减 = amount - credit，精确守恒。各降雨贡献按 rainKey 顺序依次封顶，结果稳定可复现。
     * 包可见以便单元测试直接覆盖。
     */
    static CreditCalc computeCredit(BigDecimal coefficient, List<RainRecordRow> effectiveRains,
                                    BigDecimal amount) {
        if (coefficient == null || effectiveRains.isEmpty()) {
            return new CreditCalc(BigDecimal.ZERO, amount, List.of());
        }
        List<RainRecordRow> ordered = effectiveRains.stream()
                .sorted(java.util.Comparator.comparing(RainRecordRow::rainKey)).toList();
        BigDecimal remaining = amount;
        List<AppliedRainView> applied = new ArrayList<>();
        BigDecimal creditTotal = BigDecimal.ZERO;
        for (RainRecordRow rain : ordered) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal raw = rain.rainMillis().multiply(coefficient).setScale(3, RoundingMode.HALF_UP);
            BigDecimal credit = raw.min(remaining);
            if (credit.signum() > 0) {
                applied.add(new AppliedRainView(rain.rainKey(), fmt(rain.rainMillis()), fmt(credit)));
                creditTotal = creditTotal.add(credit);
                remaining = remaining.subtract(credit);
            }
        }
        return new CreditCalc(creditTotal, amount.subtract(creditTotal), List.copyOf(applied));
    }

    private CreditCalc computeCredit(AllocationRow allocation, List<RainRecordRow> effectiveRains,
                                     BigDecimal effectiveRainMillis, BigDecimal amount) {
        return computeCredit(allocation.creditCoefficient(), effectiveRains, amount);
    }

    /** 解析降雨量（毫米）：非法格式 400；零或负值 422；最多 3 位小数。 */
    static BigDecimal parseRainMillis(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", field + " 不能为空");
        }
        String trimmed = value.trim();
        if (!SIGNED_DECIMAL3_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal amount = new BigDecimal(trimmed);
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("RAIN_NON_POSITIVE",
                    field + " 必须大于 0，零或负的降雨量不能登记: " + value);
        }
        return amount;
    }

    /** 解析可选非负系数（立方米/毫米，最多 6 位小数）：null/空白表示不参与；负值 422；格式错误 400。 */
    static BigDecimal parseOptionalNonNegativeDecimal(String field, String value, int scale) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Pattern pattern = scale == 6 ? SIGNED_DECIMAL6_PATTERN : SIGNED_DECIMAL3_PATTERN;
        if (!pattern.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    field + " 必须为非负十进制字符串，最多 " + scale + " 位小数: " + value);
        }
        BigDecimal decimal = new BigDecimal(trimmed);
        if (decimal.signum() < 0) {
            throw ApiException.unprocessable("COEFFICIENT_NEGATIVE",
                    field + " 不允许为负: " + value);
        }
        return decimal;
    }

    static long nowNanos() {
        return toNanos(Instant.now());
    }

    private long nowNanosTicks() {
        return toNanos(clock.get());
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

    /** 毫米/3 位小数量值格式化（去尾零，null 安全）。 */
    static String fmt3(BigDecimal value) {
        return value == null ? null : fmt(value);
    }

    /** 系数（6 位小数）格式化为十进制字符串（去尾零）。 */
    static String fmt6(BigDecimal value) {
        return fmt(value);
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
