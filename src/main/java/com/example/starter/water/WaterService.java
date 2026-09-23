package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SettlementInstructionRow;
import com.example.starter.water.WaterRepository.SettlementLegRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementHistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementInstructionRequest;
import com.example.starter.water.dto.Dtos.SettlementInstructionResponse;
import com.example.starter.water.dto.Dtos.SettlementLegResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SettlementVersionEntry;
import com.example.starter.water.dto.Dtos.TransferListResponse;
import com.example.starter.water.dto.Dtos.TransferResponse;
import com.example.starter.water.dto.Dtos.WindowResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        String params = "ALLOCATION_SUBMIT|" + allocationKey + "|" + windowId + "|" + userId + "|"
                + qty.toPlainString() + "|" + actor;
        return runCommand("ALLOCATION_SUBMIT", commandKey, params, AllocationResponse.class, () -> {
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
    // 批量净额清算
    // ------------------------------------------------------------------

    private static final int MAX_SETTLEMENT_INSTRUCTIONS = 100;
    private static final Pattern INTEGER_VOLUME_PATTERN = Pattern.compile("\\d{1,16}");

    /**
     * 同窗口批量净额清算：在单事务的一致读视图中校验全部指令与主体版本，按主体计算净额，
     * 一次更新所有非零净额主体；所有涉及主体（含净额为 0 的环主体）版本均加一；
     * 保存输入顺序指令、净额及前后余额的不可变快照。任一校验失败整批回滚，无任何记录。
     */
    public SettlementResponse settleBatch(String requestId, String settlementKey, Long windowId,
                                          List<SettlementInstructionRequest> instructions,
                                          List<SettlementVersionEntry> versions) {
        requireKey("requestId", requestId);
        requireKey("settlementKey", settlementKey);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        List<SettlementInstructionRequest> input = instructions == null ? List.of() : instructions;
        if (input.isEmpty() || input.size() > MAX_SETTLEMENT_INSTRUCTIONS) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "instructions 数量必须在 1～" + MAX_SETTLEMENT_INSTRUCTIONS + " 条之间");
        }
        // 规范化：指令按输入顺序；版本集合按键排序，均纳入幂等参数串
        List<BigDecimal> volumes = new ArrayList<>(input.size());
        Set<String> subjectKeys = new LinkedHashSet<>();
        Set<String> instructionKeySet = new LinkedHashSet<>();
        StringBuilder params = new StringBuilder("SETTLEMENT|").append(settlementKey).append('|')
                .append(windowId).append('|').append(input.size()).append('|');
        for (int i = 0; i < input.size(); i++) {
            SettlementInstructionRequest ins = input.get(i);
            if (ins == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "第 " + i + " 条指令为空");
            }
            requireKey("instructionKey", ins.instructionKey());
            requireKey("from", ins.from());
            requireKey("to", ins.to());
            BigDecimal volume = parseIntegerVolume(ins.volume());
            if (ins.from().equals(ins.to())) {
                throw ApiException.conflict("SETTLEMENT_SELF_TRANSFER",
                        "第 " + i + " 条指令禁止自转: " + ins.from());
            }
            if (!instructionKeySet.add(ins.instructionKey())) {
                throw ApiException.conflict("SETTLEMENT_DUPLICATE_INSTRUCTION_KEY",
                        "批次内 instructionKey 重复: " + ins.instructionKey());
            }
            volumes.add(volume);
            subjectKeys.add(ins.from());
            subjectKeys.add(ins.to());
            params.append(i).append(':').append(ins.instructionKey()).append(',')
                    .append(ins.from()).append('>').append(ins.to()).append('=').append(volume.toPlainString())
                    .append(';');
        }
        params.append('|');
        Map<String, Long> versionMap = new TreeMap<>();
        if (versions != null) {
            for (SettlementVersionEntry entry : versions) {
                if (entry == null) {
                    throw ApiException.badRequest("INVALID_ARGUMENT", "versions 中存在空项");
                }
                requireKey("allocationKey", entry.allocationKey());
                if (entry.version() < 1) {
                    throw ApiException.badRequest("INVALID_ARGUMENT",
                            "version 必须为正整数: " + entry.allocationKey());
                }
                if (versionMap.put(entry.allocationKey(), entry.version()) != null) {
                    throw ApiException.conflict("SETTLEMENT_DUPLICATE_VERSION",
                            "versions 中主体重复: " + entry.allocationKey());
                }
            }
        }
        for (Map.Entry<String, Long> entry : versionMap.entrySet()) {
            params.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        // 版本集合必须与指令涉及主体集合完全一致：遗漏或多余均拒绝
        if (!versionMap.keySet().equals(subjectKeys)) {
            Set<String> missing = new LinkedHashSet<>(subjectKeys);
            missing.removeAll(versionMap.keySet());
            Set<String> extra = new LinkedHashSet<>(versionMap.keySet());
            extra.removeAll(subjectKeys);
            throw ApiException.conflict("SETTLEMENT_VERSION_MISMATCH",
                    "主体版本集合必须恰好覆盖全部涉及主体；遗漏: " + missing + "，多余: " + extra);
        }

        String canonicalParams = params.toString();
        return runCommand("SETTLEMENT", requestId, canonicalParams, SettlementResponse.class, () -> {
            // 先锁窗口行，与单笔转让、批准、限供、其他清算按事务提交顺序串行裁决
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            // settlementKey 全局唯一（换 requestId 复用也拒绝）
            if (repository.findSettlementByKey(settlementKey) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被其他批次使用: " + settlementKey);
            }
            // 指令键全局唯一：已被其他成功批次使用则整批拒绝（失败批次不占键，不会落库）
            int usedKeys = repository.countUsedInstructionKeys(instructionKeySet);
            if (usedKeys > 0) {
                throw ApiException.conflict("SETTLEMENT_INSTRUCTION_KEY_REUSED",
                        "存在 " + usedKeys + " 个 instructionKey 已被其他批次使用");
            }
            // 一致读视图：按主键顺序锁定全部涉及主体
            List<AllocationRow> locked = repository.lockAllocationsByKeys(subjectKeys);
            Map<String, AllocationRow> rows = new LinkedHashMap<>();
            for (AllocationRow row : locked) {
                rows.put(row.allocationKey(), row);
            }
            for (String key : subjectKeys) {
                AllocationRow row = rows.get(key);
                if (row == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND", "清算主体不存在: " + key);
                }
                if (row.windowId() != windowId) {
                    throw ApiException.conflict("SETTLEMENT_CROSS_WINDOW",
                            "清算主体不属于窗口 " + windowId + ": " + key);
                }
                // 涉及主体必须为 APPROVED：CANCELLED 即主体限供；REQUESTED 无可用额度，
                // 若接水会使其在 REQUESTED 状态持有额度，破坏已批准总额的严格守恒
                if (!STATUS_APPROVED.equals(row.status())) {
                    throw ApiException.conflict("SETTLEMENT_SUBJECT_NOT_AVAILABLE",
                            "主体必须为 APPROVED 才能参与清算，当前 " + row.status() + ": " + key);
                }
                if (versionMap.get(key) != row.quotaVersion()) {
                    throw ApiException.conflict("SETTLEMENT_VERSION_CONFLICT",
                            "主体 " + key + " 额度版本已变化：提交 " + versionMap.get(key)
                                    + "，当前 " + row.quotaVersion());
                }
            }
            // 按主体计算净额：from 减、to 加
            Map<String, BigDecimal> net = new LinkedHashMap<>();
            for (String key : subjectKeys) {
                net.put(key, BigDecimal.ZERO);
            }
            BigDecimal totalVolume = BigDecimal.ZERO;
            for (int i = 0; i < input.size(); i++) {
                SettlementInstructionRequest ins = input.get(i);
                BigDecimal volume = volumes.get(i);
                totalVolume = totalVolume.add(volume);
                net.put(ins.from(), net.get(ins.from()).subtract(volume));
                net.put(ins.to(), net.get(ins.to()).add(volume));
            }
            // 一致视图内计算清算后可用额度：任一转出方清算后为负即整批 422
            for (Map.Entry<String, BigDecimal> entry : net.entrySet()) {
                AllocationRow row = rows.get(entry.getKey());
                BigDecimal after = row.heldAmount().add(entry.getValue());
                if (after.signum() < 0) {
                    throw ApiException.quotaExceeded("主体 " + entry.getKey() + " 清算后可用额度为负：当前 "
                            + fmt(row.heldAmount()) + "，净变化 " + fmt(entry.getValue()));
                }
            }
            long now = nowNanos();
            long settlementId;
            try {
                settlementId = repository.insertSettlement(settlementKey, windowId, input.size(),
                        totalVolume, now);
            } catch (DuplicateKeyException e) {
                // 并发复用同一 settlementKey（换 requestId）：整批回滚
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被其他批次使用: " + settlementKey);
            }
            // 保存输入顺序的原始指令快照
            try {
                for (int i = 0; i < input.size(); i++) {
                    SettlementInstructionRequest ins = input.get(i);
                    repository.insertSettlementInstruction(settlementId, i, ins.instructionKey(),
                            ins.from(), ins.to(), volumes.get(i));
                }
            } catch (DuplicateKeyException e) {
                // 并发批次抢先使用同一 instructionKey：整批回滚，失败不占键
                throw ApiException.conflict("SETTLEMENT_INSTRUCTION_KEY_REUSED",
                        "存在 instructionKey 已被其他批次使用");
            }
            // 一次更新所有非零净额主体；净额为 0 的主体仅版本加一；逐主体保存前后快照
            for (String key : subjectKeys) {
                AllocationRow row = rows.get(key);
                BigDecimal change = net.get(key);
                BigDecimal afterHeld = row.heldAmount().add(change);
                if (change.signum() != 0) {
                    int updated = repository.applySettlementNet(key, change, now);
                    if (updated != 1) {
                        // 防御性：锁内透支不应发生，发生则整批回滚
                        throw ApiException.quotaExceeded("主体 " + key + " 清算更新失败（额度不足）");
                    }
                } else {
                    repository.bumpAllocationVersion(key, now);
                }
                repository.insertSettlementLeg(settlementId, key, change, row.heldAmount(), afterHeld,
                        row.quotaVersion(), row.quotaVersion() + 1);
            }
            return toSettlementResponse(repository.findSettlementByKey(settlementKey));
        });
    }

    /** 查询清算批次详情（只读，不可变快照）。 */
    public SettlementResponse getSettlement(String settlementKey) {
        requireKey("settlementKey", settlementKey);
        SettlementRow row = repository.findSettlementByKey(settlementKey);
        if (row == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "清算批次不存在: " + settlementKey);
        }
        return toSettlementResponse(row);
    }

    /** 按主体查询其参与过的全部清算批次（只读，按提交顺序）。主体不存在返回 404。 */
    public SettlementHistoryResponse getSettlementHistory(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listSettlementsByAllocationKey(allocationKey)
                .stream().map(this::toSettlementResponse).toList();
        return new SettlementHistoryResponse(allocationKey, settlements);
    }

    /** 解析正整数体积（无小数位）。 */
    static BigDecimal parseIntegerVolume(String value) {
        if (value == null || value.isBlank() || !INTEGER_VOLUME_PATTERN.matcher(value.trim()).matches()) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "volume 必须为正整数体积字符串: " + value);
        }
        BigDecimal volume = new BigDecimal(value.trim());
        if (volume.signum() <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "volume 必须大于 0");
        }
        return volume;
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        List<SettlementInstructionResponse> instructions =
                repository.listSettlementInstructions(row.id()).stream().map(this::toInstructionResponse).toList();
        List<SettlementLegResponse> legs = repository.listSettlementLegs(row.id()).stream()
                .map(this::toLegResponse).toList();
        return new SettlementResponse(row.settlementKey(), row.windowId(), row.instructionCount(),
                fmt(row.totalVolume()), instructions, legs, toIso(row.createdNanos()));
    }

    private SettlementInstructionResponse toInstructionResponse(SettlementInstructionRow row) {
        return new SettlementInstructionResponse(row.instructionIndex(), row.instructionKey(),
                row.fromAllocationKey(), row.toAllocationKey(), fmt(row.volume()));
    }

    private SettlementLegResponse toLegResponse(SettlementLegRow row) {
        return new SettlementLegResponse(row.allocationKey(), fmt(row.netChange()), fmt(row.beforeHeld()),
                fmt(row.afterHeld()), row.beforeVersion(), row.afterVersion());
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
                fmt(row.heldAmount()), row.quotaVersion(), row.requester(), row.status(),
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
