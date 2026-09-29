package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.RainfallConfirmationRow;
import com.example.starter.water.WaterRepository.RainfallRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.WaterRepository.WriteOffRainRow;
import com.example.starter.water.WaterRepository.WriteOffRow;
import com.example.starter.water.dto.Dtos.AllocationDiagnosticResponse;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.DiagnosticsResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.RainSnapshotResponse;
import com.example.starter.water.dto.Dtos.RainfallConfirmationResponse;
import com.example.starter.water.dto.Dtos.RainfallListResponse;
import com.example.starter.water.dto.Dtos.RainfallResponse;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.example.starter.water.dto.Dtos.WriteOffBatchResponse;
import com.example.starter.water.dto.Dtos.WriteOffItem;
import com.example.starter.water.dto.Dtos.WriteOffListResponse;
import com.example.starter.water.dto.Dtos.WriteOffResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
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
 * 同键同参重放返回首次结果，同键改参返回 409，失败不占键。幂等指纹为规范化 JSON：
 * 包含操作类型、操作者、规范化对象集合（按键排序的数组，不使用未转义分隔符拼接）
 * 及所有影响结果的字段。</p>
 *
 * <p>并发：所有写操作在同一事务内先对窗口行 SELECT ... FOR UPDATE，
 * 按事务提交顺序生效；降雨确认/撤销、系数声明、转让与核销均在窗口锁内裁决，
 * 批量核销先校验完整最终余额再在同一事务提交。</p>
 *
 * <p>降雨抵扣：核销先算降雨抵扣（有效降雨量乘申请系数，以本次核销量为上限）再扣配额，
 * 守恒式 抵扣 + 配额扣减 = 原核销量；核销固化降雨记录、系数、抵扣与实际扣减，
 * 事后撤销降雨不改写已结算核销。所有时间为 UTC，区间左闭右开。</p>
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
    static final int REQUIRED_CONFIRMATIONS = 2;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern SIGNED_AMOUNT_PATTERN = Pattern.compile("-?\\d{1,16}(\\.\\d{1,3})?");
    private static final Pattern COEFFICIENT_PATTERN = Pattern.compile("-?\\d{1,16}(\\.\\d{1,6})?");
    private static final Pattern KEY_PATTERN = Pattern.compile("[\\w.\\-:]{1,128}");

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    /** 业务时钟，默认 UTC 系统时钟；测试可替换为可控时钟。 */
    private Clock clock = Clock.systemUTC();

    public WaterService(WaterRepository repository, PlatformTransactionManager transactionManager,
                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 替换业务时钟（仅测试使用），所有业务时间戳统一从该时钟读取 UTC 时刻。 */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // 命令入口：窗口 / 申请 / 限供 / 转让
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
        String fingerprint = fingerprint("WINDOW_CREATE", orderedMap(
                "windowKey", windowKey, "channelId", channelId, "startNanos", startNanos,
                "endNanos", endNanos, "plannedVolume", planned.toPlainString()));
        return runCommand("WINDOW_CREATE", commandKey, fingerprint, WindowResponse.class, () -> {
            if (repository.existsOverlappingWindow(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("WINDOW_OVERLAP", "同一渠道存在时间重叠的供水窗口");
            }
            long id = repository.insertWindow(windowKey, channelId, startNanos, endNanos, planned, nowNanos());
            WindowRow row = repository.findWindowById(id);
            return toWindowResponse(row, null);
        });
    }

    /** 提交配水申请，申请人为 actor。 */
    public AllocationResponse submitAllocation(String commandKey, String allocationKey, Long windowId,
                                               String userId, String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("userId", userId);
        requireKey("X-Actor-Id", actor);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        BigDecimal qty = parseAmount("amount", amount);
        String fingerprint = fingerprint("ALLOCATION_SUBMIT", orderedMap(
                "allocationKey", allocationKey, "windowId", windowId, "userId", userId,
                "amount", qty.toPlainString(), "actor", actor));
        return runCommand("ALLOCATION_SUBMIT", commandKey, fingerprint, AllocationResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long id = repository.insertAllocation(allocationKey, windowId, userId, qty, actor, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    /** 批准申请：加入本申请后已批准总量不得超过当前可用总量，否则 422。 */
    public AllocationResponse approveAllocation(String commandKey, String allocationKey) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        String fingerprint = fingerprint("ALLOCATION_APPROVE", orderedMap("allocationKey", allocationKey));
        return runCommand("ALLOCATION_APPROVE", commandKey, fingerprint, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/取消/核销并发时使用过期状态与持有额度
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
        String fingerprint = fingerprint("ALLOCATION_CANCEL", orderedMap(
                "allocationKey", allocationKey, "actor", actor));
        return runCommand("ALLOCATION_CANCEL", commandKey, fingerprint, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            // 窗口锁内重读申请，避免与转让/核销/并发取消使用过期状态
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
        String fingerprint = fingerprint("TRANSFER", orderedMap(
                "transferKey", transferKey, "sourceAllocationKey", sourceAllocationKey,
                "targetAllocationKey", targetAllocationKey, "actor", actor));
        return runCommand("TRANSFER", commandKey, fingerprint, TransferResponse.class, () -> {
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
            // 先锁窗口行，与普通批准、取消、限供调整、核销按事务提交顺序串行裁决
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
        requireWindow(windowId);
        List<TransferResponse> transfers = repository.listTransfers(windowId).stream()
                .map(this::toTransferResponse).toList();
        return new TransferListResponse(windowId, transfers);
    }

    /** 创建限供：仅当当前已批准总量不超过拟定限供水量时允许。 */
    public CurtailmentResponse createCurtailment(String commandKey, long windowId, String volume) {
        requireKey("commandKey", commandKey);
        BigDecimal qty = parseAmount("volume", volume);
        String fingerprint = fingerprint("CURTAILMENT_CREATE", orderedMap(
                "windowId", windowId, "volume", qty.toPlainString()));
        return runCommand("CURTAILMENT_CREATE", commandKey, fingerprint, CurtailmentResponse.class, () -> {
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
        String fingerprint = fingerprint("CURTAILMENT_CANCEL", orderedMap("windowId", windowId));
        return runCommand("CURTAILMENT_CANCEL", commandKey, fingerprint, CurtailmentResponse.class, () -> {
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
        WindowRow window = requireWindow(windowId);
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        BigDecimal available = active != null ? active.volume() : window.plannedVolume();
        BigDecimal approved = repository.sumApprovedAmount(windowId);
        return new CapacityResponse(window.id(), fmt(window.plannedVolume()),
                active != null ? fmt(active.volume()) : null, fmt(available), fmt(approved),
                fmt(available.subtract(approved)));
    }

    // ------------------------------------------------------------------
    // 命令入口：降雨登记 / 双角色确认 / 撤销 / 抵扣系数
    // ------------------------------------------------------------------

    /**
     * 登记窗口经复核的有效降雨量（初始 PENDING）。
     * 降雨量为负返回 422；同窗口已存在生效记录返回 422。登记操作人由 X-Actor-Id 提供。
     */
    public RainfallResponse registerRainfall(String commandKey, long windowId, String rainKey,
                                             String rainfallMm, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal mm = parseSignedRainfall(rainfallMm);
        String fingerprint = fingerprint("RAIN_REGISTER", orderedMap(
                "windowId", windowId, "rainKey", rainKey, "rainfallMm", mm.toPlainString(), "actor", actor));
        return runCommand("RAIN_REGISTER", commandKey, fingerprint, RainfallResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            if (repository.findRainfallByKey(rainKey) != null) {
                throw ApiException.conflict("RAIN_KEY_REUSED", "rainKey 已被使用: " + rainKey);
            }
            // 同窗口至多一条生效记录：登记 PENDING 不拦截，在第二名角色确认使其生效时复验
            long id = repository.insertRainfall(rainKey, windowId, mm, actor, nowNanos());
            return toRainfallResponse(repository.listRainfall(windowId).stream()
                    .filter(r -> r.id() == id).findFirst().orElseThrow());
        });
    }

    /**
     * 水务角色确认降雨记录；同一记录须两名不同角色确认才生效，同角色重复确认返回 409。
     * 窗口不匹配返回 422；确认时若同窗口已有其他生效记录返回 422。
     */
    public RainfallResponse confirmRainfall(String commandKey, long windowId, String rainKey,
                                            String roleId, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("roleId", roleId);
        requireKey("X-Actor-Id", actor);
        String fingerprint = fingerprint("RAIN_CONFIRM", orderedMap(
                "windowId", windowId, "rainKey", rainKey, "roleId", roleId, "actor", actor));
        return runCommand("RAIN_CONFIRM", commandKey, fingerprint, RainfallResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            RainfallRow rain = repository.lockRainfallByKey(rainKey);
            if (rain == null) {
                throw ApiException.notFound("RAINFALL_NOT_FOUND", "降雨记录不存在: " + rainKey);
            }
            if (rain.windowId() != windowId) {
                throw ApiException.unprocessable("RAIN_WINDOW_MISMATCH",
                        "降雨记录不属于路径指定窗口: 记录窗口 " + rain.windowId() + "，请求窗口 " + windowId);
            }
            if (RAIN_REVOKED.equals(rain.status())) {
                throw ApiException.conflict("RAIN_REVOKED", "已撤销的降雨记录不能确认");
            }
            if (RAIN_EFFECTIVE.equals(rain.status())) {
                throw ApiException.conflict("RAIN_ALREADY_EFFECTIVE", "降雨记录已生效，不能重复确认");
            }
            if (repository.existsConfirmation(rain.id(), roleId)) {
                throw ApiException.conflict("RAIN_ROLE_ALREADY_CONFIRMED",
                        "该水务角色已确认过此降雨记录: " + roleId);
            }
            long now = nowNanos();
            try {
                repository.insertConfirmation(rain.id(), roleId, actor, now);
            } catch (DuplicateKeyException e) {
                // 同角色并发确认：事务回滚
                throw ApiException.conflict("RAIN_ROLE_ALREADY_CONFIRMED",
                        "该水务角色已确认过此降雨记录: " + roleId);
            }
            List<RainfallConfirmationRow> confirmations = repository.listConfirmations(rain.id());
            if (confirmations.size() >= REQUIRED_CONFIRMATIONS) {
                // 生效前复验：同窗口不得已有其他生效记录
                for (RainfallRow other : repository.lockEffectiveRainfall(windowId)) {
                    if (other.id() != rain.id()) {
                        throw ApiException.unprocessable("RAIN_DUPLICATE_EFFECTIVE",
                                "同窗口已存在生效中的降雨记录，本记录不能再生效");
                    }
                }
                repository.markRainfallEffective(rain.id(), confirmations.size(), now);
            } else {
                repository.updateRainfallConfirmCount(rain.id(), confirmations.size());
            }
            return toRainfallResponse(repository.findRainfallByKey(rainKey));
        });
    }

    /**
     * 撤销降雨记录（PENDING/EFFECTIVE 均可）。撤销后不可恢复；已结算核销固化的降雨证据
     * 与抵扣量不随之改写。
     */
    public RainfallResponse revokeRainfall(String commandKey, long windowId, String rainKey, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("rainKey", rainKey);
        requireKey("X-Actor-Id", actor);
        String fingerprint = fingerprint("RAIN_REVOKE", orderedMap(
                "windowId", windowId, "rainKey", rainKey, "actor", actor));
        return runCommand("RAIN_REVOKE", commandKey, fingerprint, RainfallResponse.class, () -> {
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            RainfallRow rain = repository.lockRainfallByKey(rainKey);
            if (rain == null) {
                throw ApiException.notFound("RAINFALL_NOT_FOUND", "降雨记录不存在: " + rainKey);
            }
            if (rain.windowId() != windowId) {
                throw ApiException.unprocessable("RAIN_WINDOW_MISMATCH",
                        "降雨记录不属于路径指定窗口: 记录窗口 " + rain.windowId() + "，请求窗口 " + windowId);
            }
            if (RAIN_REVOKED.equals(rain.status())) {
                throw ApiException.conflict("RAIN_ALREADY_REVOKED", "降雨记录已撤销，不能重复撤销");
            }
            repository.revokeRainfall(rain.id(), actor, nowNanos());
            return toRainfallResponse(repository.findRainfallByKey(rainKey));
        });
    }

    /** 查询窗口全部降雨记录（含已撤销），按登记顺序返回。 */
    public RainfallListResponse getRainfall(long windowId) {
        requireWindow(windowId);
        List<RainfallResponse> records = repository.listRainfall(windowId).stream()
                .map(rain -> toRainfallResponse(rain, repository.listConfirmations(rain.id()))).toList();
        return new RainfallListResponse(windowId, records);
    }

    /**
     * 申请声明每毫米有效降雨对应的用水抵扣系数（立方米/毫米），仅申请人本人可声明。
     * 系数为负返回 422；零表示不享受抵扣。核销后修改系数不影响已固化核销。
     */
    public AllocationResponse declareCreditFactor(String commandKey, String allocationKey,
                                                  String coefficient, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal factor = parseCoefficient(coefficient);
        String fingerprint = fingerprint("CREDIT_FACTOR", orderedMap(
                "allocationKey", allocationKey, "coefficient", factor.toPlainString(), "actor", actor));
        return runCommand("CREDIT_FACTOR", commandKey, fingerprint, AllocationResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            lockWindowOf(allocation);
            allocation = repository.lockAllocationByKey(allocationKey);
            if (!allocation.requester().equals(actor)) {
                throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以声明抵扣系数");
            }
            if (STATUS_CANCELLED.equals(allocation.status())) {
                throw ApiException.conflict("ALLOCATION_CANCELLED", "已取消的申请不能声明抵扣系数");
            }
            repository.updateCreditCoefficient(allocation.id(), factor, nowNanos());
            return toAllocationResponse(repository.findAllocationByKey(allocationKey));
        });
    }

    // ------------------------------------------------------------------
    // 命令入口：单笔与批量核销
    // ------------------------------------------------------------------

    /** 单笔核销内部计划：先算降雨抵扣，再得到配额扣减。 */
    private record WriteOffPlan(WriteOffItem item, AllocationRow allocation, BigDecimal effectiveRainMm,
                                BigDecimal credit, BigDecimal quotaDeduction) {
    }

    /**
     * 单笔核销：先按窗口当前生效降雨量与申请系数计算降雨抵扣（以本次核销量为上限），
     * 再扣减配额；配额不足返回 422（携带持有额度实际值、所需配额扣减要求值与差额）。
     * 降雨记录、系数、抵扣与实际扣减在同一事务固化。
     */
    public WriteOffResponse writeOff(String commandKey, long windowId, String writeOffKey,
                                     String allocationKey, String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("writeOffKey", writeOffKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal requested = parseAmount("amount", amount);
        WriteOffItem item = new WriteOffItem(writeOffKey, allocationKey, requested.toPlainString());
        String fingerprint = writeOffFingerprint("WRITE_OFF", windowId, null, actor, List.of(item));
        return runCommand("WRITE_OFF", commandKey, fingerprint, WriteOffResponse.class, () -> {
            List<WriteOffResponse> responses = executeWriteOffBatch(windowId, null, List.of(item), actor);
            return responses.get(0);
        });
    }

    /**
     * 批量核销：所有项必须属于同一窗口；先锁定窗口与全部涉及申请，按最终余额逐项校验
     * （同一申请多笔合并验证），全部通过后在一个事务内提交；任一项失败整体回滚，无半成品。
     */
    public WriteOffBatchResponse writeOffBatch(String commandKey, long windowId, String batchKey,
                                               List<WriteOffItem> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("batchKey", batchKey);
        requireKey("X-Actor-Id", actor);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 至少包含一笔核销");
        }
        List<WriteOffItem> normalized = new ArrayList<>();
        for (WriteOffItem item : items) {
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "核销项不能为空");
            }
            requireKey("writeOffKey", item.writeOffKey());
            requireKey("allocationKey", item.allocationKey());
            normalized.add(new WriteOffItem(item.writeOffKey(), item.allocationKey(),
                    parseAmount("amount", item.amount()).toPlainString()));
        }
        String fingerprint = writeOffFingerprint("WRITE_OFF_BATCH", windowId, batchKey, actor, normalized);
        return runCommand("WRITE_OFF_BATCH", commandKey, fingerprint, WriteOffBatchResponse.class, () -> {
            List<WriteOffResponse> responses = executeWriteOffBatch(windowId, batchKey, normalized, actor);
            BigDecimal requestedTotal = BigDecimal.ZERO;
            BigDecimal creditTotal = BigDecimal.ZERO;
            BigDecimal quotaTotal = BigDecimal.ZERO;
            for (WriteOffResponse response : responses) {
                requestedTotal = requestedTotal.add(new BigDecimal(response.requestedAmount()));
                creditTotal = creditTotal.add(new BigDecimal(response.creditAmount()));
                quotaTotal = quotaTotal.add(new BigDecimal(response.quotaDeduction()));
            }
            return new WriteOffBatchResponse(batchKey, windowId, responses, fmt(requestedTotal),
                    fmt(creditTotal), fmt(quotaTotal));
        });
    }

    /** 查询窗口全部核销流水（不可变），按核销顺序返回。 */
    public WriteOffListResponse getWriteOffs(long windowId) {
        requireWindow(windowId);
        List<WriteOffResponse> rows = repository.listWriteOffs(windowId).stream()
                .map(this::toWriteOffResponse).toList();
        return new WriteOffListResponse(windowId, rows);
    }

    // ------------------------------------------------------------------
    // 查询：历史与诊断
    // ------------------------------------------------------------------

    /** 查询窗口历史明细：窗口 + 全部申请 + 全部限供 + 全部降雨记录 + 全部核销流水。 */
    public HistoryResponse getHistory(long windowId) {
        WindowRow window = requireWindow(windowId);
        CurtailmentRow active = repository.findActiveCurtailment(windowId);
        List<AllocationResponse> allocations = repository.listAllocations(windowId).stream()
                .map(this::toAllocationResponse).toList();
        List<CurtailmentResponse> curtailments = repository.listCurtailments(windowId).stream()
                .map(this::toCurtailmentResponse).toList();
        List<RainfallResponse> rainfall = repository.listRainfall(windowId).stream()
                .map(rain -> toRainfallResponse(rain, repository.listConfirmations(rain.id()))).toList();
        List<WriteOffResponse> writeOffs = repository.listWriteOffs(windowId).stream()
                .map(this::toWriteOffResponse).toList();
        return new HistoryResponse(toWindowResponse(window, active), allocations, curtailments,
                rainfall, writeOffs);
    }

    /**
     * 窗口核销诊断：当前生效降雨量、核销合计（原核销量/抵扣/配额扣减）及逐申请诊断行，
     * 申请按主键升序稳定排序。
     */
    public DiagnosticsResponse getDiagnostics(long windowId) {
        requireWindow(windowId);
        List<RainfallRow> effective = repository.listRainfall(windowId).stream()
                .filter(r -> RAIN_EFFECTIVE.equals(r.status())).toList();
        BigDecimal effectiveRain = effective.stream().map(RainfallRow::rainfallMm)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requestedTotal = repository.sumWriteOffColumn(windowId, "requested_amount");
        BigDecimal creditTotal = repository.sumWriteOffColumn(windowId, "credit_amount");
        BigDecimal quotaTotal = repository.sumWriteOffColumn(windowId, "quota_deduction");
        List<AllocationDiagnosticResponse> rows = repository.listAllocations(windowId).stream()
                .map(a -> {
                    BigDecimal coeff = a.creditCoefficient() == null ? BigDecimal.ZERO : a.creditCoefficient();
                    BigDecimal availableCredit = creditFor(effectiveRain, coeff);
                    return new AllocationDiagnosticResponse(a.allocationKey(), a.status(), fmt(a.heldAmount()),
                            a.creditCoefficient() == null ? null : fmt(a.creditCoefficient()),
                            fmt(effectiveRain), fmt(availableCredit),
                            fmt(repository.sumCreditByAllocation(a.allocationKey())),
                            fmt(repository.sumQuotaDeductionByAllocation(a.allocationKey())),
                            repository.countWriteOffsByAllocation(a.allocationKey()));
                }).toList();
        return new DiagnosticsResponse(windowId, fmt(effectiveRain), fmt(requestedTotal), fmt(creditTotal),
                fmt(quotaTotal), rows);
    }

    // ------------------------------------------------------------------
    // 核销事务核心
    // ------------------------------------------------------------------

    /**
     * 在调用方事务（runCommand 的单事务）内执行核销批次：
     * 锁窗口 -> 锁生效降雨 -> 按申请主键升序锁定全部涉及申请 -> 校验完整最终状态与最终余额 ->
     * 逐笔扣减并固化流水与降雨证据。
     */
    private List<WriteOffResponse> executeWriteOffBatch(long windowId, String batchKey,
                                                        List<WriteOffItem> items, String actor) {
        WindowRow window = repository.lockWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        if (batchKey != null && repository.findWriteOffByBatchKey(batchKey) != null) {
            throw ApiException.conflict("BATCH_KEY_REUSED", "batchKey 已被使用: " + batchKey);
        }
        for (WriteOffItem item : items) {
            if (repository.findWriteOffByKey(item.writeOffKey()) != null) {
                throw ApiException.conflict("WRITE_OFF_KEY_REUSED",
                        "writeOffKey 已被使用: " + item.writeOffKey());
            }
        }
        // 锁定窗口当前全部生效降雨记录（核销的降雨基数），与确认/撤销串行
        List<RainfallRow> effectiveRain = repository.lockEffectiveRainfall(windowId);
        BigDecimal effectiveRainMm = effectiveRain.stream().map(RainfallRow::rainfallMm)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 涉及申请先解析、按主键升序去重后再锁定，避免多笔/并发死锁，并得到最终状态
        Map<String, AllocationRow> locked = new LinkedHashMap<>();
        List<AllocationRow> resolved = new ArrayList<>();
        for (String key : items.stream().map(WriteOffItem::allocationKey).collect(
                java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))) {
            AllocationRow row = repository.findAllocationByKey(key);
            if (row == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + key);
            }
            resolved.add(row);
        }
        resolved.sort(Comparator.comparingLong(AllocationRow::id));
        for (AllocationRow existing : resolved) {
            AllocationRow row = repository.lockAllocationByKey(existing.allocationKey());
            if (row.windowId() != windowId) {
                throw ApiException.unprocessable("ALLOCATION_WINDOW_MISMATCH",
                        "配水申请不属于核销窗口: " + row.allocationKey());
            }
            if (!STATUS_APPROVED.equals(row.status())) {
                throw ApiException.conflict("ALLOCATION_NOT_APPROVED",
                        "只有 APPROVED 状态的申请可以核销: " + row.allocationKey());
            }
            locked.put(row.allocationKey(), row);
        }

        // 先计算完整最终计划：每笔的抵扣与配额扣减，以及每个申请的合并配额扣减
        List<WriteOffPlan> plans = new ArrayList<>();
        Map<String, BigDecimal> quotaByAllocation = new LinkedHashMap<>();
        for (WriteOffItem item : items) {
            AllocationRow allocation = locked.get(item.allocationKey());
            BigDecimal requested = new BigDecimal(item.amount());
            BigDecimal coeff = allocation.creditCoefficient() == null
                    ? BigDecimal.ZERO : allocation.creditCoefficient();
            BigDecimal credit = creditFor(effectiveRainMm, coeff).min(requested);
            BigDecimal quotaDeduction = requested.subtract(credit);
            quotaByAllocation.merge(item.allocationKey(), quotaDeduction, BigDecimal::add);
            plans.add(new WriteOffPlan(item, allocation, effectiveRainMm, credit, quotaDeduction));
        }
        // 先验最终余额：每个申请的合并配额扣减不得超过当前持有额度
        for (Map.Entry<String, BigDecimal> entry : quotaByAllocation.entrySet()) {
            AllocationRow allocation = locked.get(entry.getKey());
            BigDecimal required = entry.getValue();
            if (allocation.heldAmount().compareTo(required) < 0) {
                throw ApiException.unprocessable("WRITE_OFF_QUOTA_EXCEEDED",
                        "配额扣减超过申请当前持有额度: " + entry.getKey()
                                + "，实际持有 " + fmt(allocation.heldAmount())
                                + "，要求扣减 " + fmt(required)
                                + "，差额 " + fmt(required.subtract(allocation.heldAmount())),
                        allocation.heldAmount(), required);
            }
        }

        // 全部校验通过：逐笔固化扣减与不可变流水，同一申请合并扣减一次持有额度
        long now = nowNanos();
        Map<String, BigDecimal> deducted = new LinkedHashMap<>();
        List<WriteOffResponse> responses = new ArrayList<>();
        for (WriteOffPlan plan : plans) {
            AllocationRow allocation = plan.allocation();
            BigDecimal requested = new BigDecimal(plan.item().amount());
            BigDecimal coeff = allocation.creditCoefficient() == null
                    ? BigDecimal.ZERO : allocation.creditCoefficient();
            BigDecimal already = deducted.getOrDefault(allocation.allocationKey(), BigDecimal.ZERO);
            BigDecimal heldBefore = allocation.heldAmount().subtract(already);
            BigDecimal heldAfter = heldBefore.subtract(plan.quotaDeduction());
            long writeOffId = repository.insertWriteOff(plan.item().writeOffKey(), batchKey, windowId,
                    allocation.allocationKey(), requested, plan.effectiveRainMm(), coeff, plan.credit(),
                    plan.quotaDeduction(), heldBefore, heldAfter, actor, now);
            for (RainfallRow rain : effectiveRain) {
                repository.insertWriteOffRain(writeOffId, rain.rainKey(), rain.rainfallMm(), now);
            }
            deducted.merge(allocation.allocationKey(), plan.quotaDeduction(), BigDecimal::add);
            responses.add(toWriteOffResponse(requireWriteOff(plan.item().writeOffKey())));
        }
        for (Map.Entry<String, BigDecimal> entry : deducted.entrySet()) {
            AllocationRow allocation = locked.get(entry.getKey());
            repository.decrementHeldAmount(allocation.id(), entry.getValue(), now);
        }
        return responses;
    }

    /** 计算降雨抵扣毛额：有效降雨量（毫米）× 系数（立方米/毫米），3 位小数四舍五入。 */
    static BigDecimal creditFor(BigDecimal effectiveRainMm, BigDecimal coefficient) {
        if (coefficient.signum() == 0 || effectiveRainMm.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return effectiveRainMm.multiply(coefficient).setScale(3, RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架
    // ------------------------------------------------------------------

    /**
     * 在单事务内执行幂等命令：先占位插入命令行，再执行业务并写回响应。
     * 并发同键时占位插入会因唯一约束失败，随后读取已提交的首次结果重放或报 409。
     */
    private <T> T runCommand(String operation, String commandKey, String fingerprint, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, fingerprint, type);
                }
                repository.insertCommand(commandKey, operation, fingerprint, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, fingerprint, type);
        }
    }

    private <T> T replay(CommandRow existing, String operation, String fingerprint, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(fingerprint)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private WindowRow requireWindow(long windowId) {
        WindowRow window = repository.findWindowById(windowId);
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
        }
        return window;
    }

    private WriteOffRow requireWriteOff(String writeOffKey) {
        WriteOffRow row = repository.findWriteOffByKey(writeOffKey);
        if (row == null) {
            throw new IllegalStateException("核销流水写入后查不到: " + writeOffKey);
        }
        return row;
    }

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
                fmt(row.heldAmount()), row.creditCoefficient() == null ? null : fmt(row.creditCoefficient()),
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

    private RainfallResponse toRainfallResponse(RainfallRow row) {
        return toRainfallResponse(row, repository.listConfirmations(row.id()));
    }

    private RainfallResponse toRainfallResponse(RainfallRow row, List<RainfallConfirmationRow> confirmations) {
        List<RainfallConfirmationResponse> confirmationResponses = confirmations.stream()
                .map(c -> new RainfallConfirmationResponse(c.roleId(), c.confirmer(), toIso(c.createdNanos())))
                .toList();
        return new RainfallResponse(row.id(), row.rainKey(), row.windowId(), fmt(row.rainfallMm()),
                row.status(), row.registrar(), row.confirmCount(), REQUIRED_CONFIRMATIONS,
                confirmationResponses, toIso(row.createdNanos()),
                row.effectiveNanos() == null ? null : toIso(row.effectiveNanos()),
                row.revokedNanos() == null ? null : toIso(row.revokedNanos()), row.revokedBy());
    }

    private WriteOffResponse toWriteOffResponse(WriteOffRow row) {
        List<RainSnapshotResponse> rains = repository.listWriteOffRains(row.id()).stream()
                .map(this::toRainSnapshot).toList();
        return new WriteOffResponse(row.writeOffKey(), row.batchKey(), row.windowId(), row.allocationKey(),
                fmt(row.requestedAmount()), fmt(row.rainfallMm()), fmt(row.coefficient()),
                fmt(row.creditAmount()), fmt(row.quotaDeduction()), fmt(row.heldBefore()),
                fmt(row.heldAfter()), row.actor(), toIso(row.createdNanos()), rains);
    }

    private RainSnapshotResponse toRainSnapshot(WriteOffRainRow row) {
        return new RainSnapshotResponse(row.rainKey(), fmt(row.rainfallMm()));
    }

    /** 构造按键字典序排列的 LinkedHashMap，保证幂等指纹中字段顺序稳定。 */
    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> sorted = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            sorted.put((String) kv[i], kv[i + 1]);
        }
        return new LinkedHashMap<>(sorted);
    }

    /**
     * 核销幂等指纹：操作者、操作类型、窗口及规范化对象集合。
     * 集合为按键排序后的 JSON 数组（不使用未转义分隔符拼接），包含所有影响结果的字段；
     * 批量核销额外包含 batchKey。
     */
    private String writeOffFingerprint(String operation, long windowId, String batchKey, String actor,
                                       List<WriteOffItem> items) {
        List<Map<String, Object>> normalizedItems = items.stream()
                .sorted(Comparator.comparing(WriteOffItem::writeOffKey)
                        .thenComparing(WriteOffItem::allocationKey))
                .map(item -> orderedMap("allocationKey", item.allocationKey(),
                        "amount", item.amount(), "writeOffKey", item.writeOffKey()))
                .map(m -> (Map<String, Object>) m)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Map<String, Object> root = batchKey == null
                ? orderedMap("actor", actor, "items", normalizedItems, "windowId", windowId)
                : orderedMap("actor", actor, "batchKey", batchKey, "items", normalizedItems,
                        "windowId", windowId);
        return fingerprint(operation, root);
    }

    /** 规范化指纹 JSON：字段名来自 TreeMap（字典序），集合为 JSON 数组。 */
    private String fingerprint(String operation, Map<String, Object> fields) {
        try {
            Map<String, Object> root = new TreeMap<>();
            root.put("operation", operation);
            root.putAll(fields);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
        }
    }

    long nowNanos() {
        return toNanos(Instant.now(clock));
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

    /** 解析降雨量：最多 3 位小数；为负返回 422，零或格式非法返回 400。 */
    static BigDecimal parseSignedRainfall(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "rainfallMm 不能为空");
        }
        String trimmed = value.trim();
        if (!SIGNED_AMOUNT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "rainfallMm 必须为十进制字符串，最多 3 位小数: " + value);
        }
        BigDecimal mm = new BigDecimal(trimmed);
        if (mm.signum() < 0) {
            throw ApiException.unprocessable("NEGATIVE_RAINFALL",
                    "降雨量不能为负: " + fmt(mm), mm, BigDecimal.ZERO);
        }
        if (mm.signum() == 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "rainfallMm 必须大于 0");
        }
        return mm;
    }

    /** 解析抵扣系数：最多 6 位小数，允许零；为负返回 422。 */
    private static BigDecimal parseCoefficient(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "coefficient 不能为空");
        }
        String trimmed = value.trim();
        if (!COEFFICIENT_PATTERN.matcher(trimmed).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "coefficient 必须为十进制字符串，最多 6 位小数: " + value);
        }
        BigDecimal coefficient = new BigDecimal(trimmed);
        if (coefficient.signum() < 0) {
            throw ApiException.unprocessable("NEGATIVE_COEFFICIENT",
                    "抵扣系数不能为负: " + fmt(coefficient), coefficient, BigDecimal.ZERO);
        }
        return coefficient;
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
