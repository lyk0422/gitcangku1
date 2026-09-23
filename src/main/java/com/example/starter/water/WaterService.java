package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.CommandRow;
import com.example.starter.water.WaterRepository.CurtailmentRow;
import com.example.starter.water.WaterRepository.SettlementBatchRow;
import com.example.starter.water.WaterRepository.SettlementSnapshotRow;
import com.example.starter.water.WaterRepository.TransferRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CapacityResponse;
import com.example.starter.water.dto.Dtos.CurtailmentResponse;
import com.example.starter.water.dto.Dtos.HistoryResponse;
import com.example.starter.water.dto.Dtos.SettlementInstruction;
import com.example.starter.water.dto.Dtos.SettlementInstructionView;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.example.starter.water.dto.Dtos.SubjectSettlementHistoryResponse;
import com.example.starter.water.dto.Dtos.SubjectSnapshotView;
import com.example.starter.water.dto.Dtos.SubjectVersion;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    /** 解析后的清算指令：保留输入顺序 seqNo。 */
    private record ParsedInstruction(int seqNo, String instructionKey, String from, String to,
                                    BigDecimal volume) {
    }

    /**
     * 提交同窗口批量净额清算批次。
     *
     * <p>规则：1～100 条有向指令、正整数体积、指令键批内唯一且禁止自转；提交方必须给出全部涉及主体的
     * “申请键 + 当前额度版本”完整集合。事务内先锁窗口行（与单笔转让/限供按提交顺序串行），再按 id 升序
     * 锁定全部主体行得到一致读视图：主体必须全部存在、属于同一窗口且为 APPROVED、版本完全匹配；
     * 指令键未被其他成功批次占用；按主体求净变化后任一转出方清算后持有为负则 422。
     * 成功后一次更新所有非零净额主体（净额为 0 者仅版本加一），全部涉及主体版本加一，
     * 并写入批次、原指令（输入顺序）与主体前后余额/净额的不可变快照；净额和恒为 0，总额度严格守恒。</p>
     *
     * <p>幂等：requestId 同参（含指令顺序）完全相同才重放首次结果，异参 409，失败不占任何键；
     * settlementKey 全局唯一。</p>
     */
    public SettlementResponse submitSettlement(String commandKey, String requestId, String settlementKey,
                                               Long windowId, List<SettlementInstruction> instructions,
                                               List<SubjectVersion> subjects) {
        requireKey("commandKey", commandKey);
        requireKey("requestId", requestId);
        requireKey("settlementKey", settlementKey);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        if (instructions == null || instructions.isEmpty() || instructions.size() > 100) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "instructions 必须包含 1～100 条指令");
        }
        if (subjects == null || subjects.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "subjects 必须包含全部涉及主体的版本集合");
        }

        List<ParsedInstruction> parsed = new ArrayList<>(instructions.size());
        Set<String> batchInstructionKeys = new HashSet<>();
        Set<String> involved = new TreeSet<>();
        for (int i = 0; i < instructions.size(); i++) {
            SettlementInstruction instruction = instructions.get(i);
            if (instruction == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "第 " + i + " 条指令不能为空");
            }
            requireKey("instructionKey", instruction.instructionKey());
            requireKey("from", instruction.from());
            requireKey("to", instruction.to());
            if (instruction.from().equals(instruction.to())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "第 " + i + " 条指令禁止自转: " + instruction.from());
            }
            BigDecimal volume = parseAmount("volume", instruction.volume());
            if (volume.stripTrailingZeros().scale() > 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "第 " + i + " 条指令体积必须为正整数（立方米）: " + instruction.volume());
            }
            if (!batchInstructionKeys.add(instruction.instructionKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "instructionKey 在批次内重复: " + instruction.instructionKey());
            }
            parsed.add(new ParsedInstruction(i, instruction.instructionKey(), instruction.from(),
                    instruction.to(), volume));
            involved.add(instruction.from());
            involved.add(instruction.to());
        }

        Map<String, Long> expectedVersions = new TreeMap<>();
        for (SubjectVersion subject : subjects) {
            if (subject == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "subjects 不能包含空元素");
            }
            requireKey("allocationKey", subject.allocationKey());
            if (subject.version() < 0) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "主体版本不能为负: " + subject.allocationKey());
            }
            if (expectedVersions.put(subject.allocationKey(), subject.version()) != null) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "主体在版本集合中重复: " + subject.allocationKey());
            }
        }
        if (!expectedVersions.keySet().equals(involved)) {
            throw ApiException.conflict("VERSION_SET_MISMATCH",
                    "主体版本集合必须与指令涉及主体完全一致（不得遗漏或多余）");
        }

        // 规范化参数串：指令保留输入顺序（顺序有业务意义），主体集合按键排序规范化
        StringBuilder paramsBuilder = new StringBuilder("SETTLEMENT|").append(requestId).append('|')
                .append(settlementKey).append('|').append(windowId).append('|').append(parsed.size());
        for (ParsedInstruction instruction : parsed) {
            paramsBuilder.append("|I:").append(instruction.instructionKey()).append('>')
                    .append(instruction.from()).append('>').append(instruction.to()).append('>')
                    .append(instruction.volume().toPlainString());
        }
        for (Map.Entry<String, Long> subject : expectedVersions.entrySet()) {
            paramsBuilder.append("|S:").append(subject.getKey()).append('=').append(subject.getValue());
        }
        String params = paramsBuilder.toString();

        return runCommand("SETTLEMENT_SUBMIT", commandKey, params, SettlementResponse.class, () -> {
            // requestId 独立幂等：换 commandKey 重试时，完全同参重放、异参 409
            SettlementBatchRow byRequest = repository.findSettlementByRequestId(requestId);
            if (byRequest != null) {
                CommandRow priorCommand = repository.findCommand(byRequest.commandKey());
                if (priorCommand != null && params.equals(priorCommand.params())) {
                    return toSettlementResponse(byRequest);
                }
                throw ApiException.conflict("REQUEST_ID_REUSED", "相同 requestId 但参数不同，拒绝重放");
            }
            if (repository.findSettlementByKey(settlementKey) != null) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + settlementKey);
            }

            // 窗口锁：与单笔转让、限供、普通批准按事务提交顺序串行裁决
            WindowRow window = repository.lockWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }

            // 一致读视图：按 id 升序锁定全部涉及主体，避免多批次交叉锁死锁
            List<AllocationRow> lockedRows = repository.lockAllocationsByKeys(involved);
            if (lockedRows.size() != involved.size()) {
                throw ApiException.notFound("SUBJECT_NOT_FOUND", "清算涉及的主体申请不存在");
            }
            Map<String, AllocationRow> locked = new TreeMap<>();
            for (AllocationRow row : lockedRows) {
                if (row.windowId() != windowId) {
                    throw ApiException.conflict("CROSS_WINDOW",
                            "清算指令涉及的主体必须全部属于窗口 " + windowId);
                }
                if (!STATUS_APPROVED.equals(row.status())) {
                    throw ApiException.conflict("SUBJECT_NOT_APPROVED",
                            "主体处于限供/非批准状态，不能参与清算: " + row.allocationKey());
                }
                long expected = expectedVersions.get(row.allocationKey());
                if (row.version() != expected) {
                    throw ApiException.conflict("VERSION_MISMATCH",
                            "主体 " + row.allocationKey() + " 当前版本 " + row.version()
                                    + " 与提交版本 " + expected + " 不一致");
                }
                locked.put(row.allocationKey(), row);
            }

            // 指令键全局唯一：已被其他成功批次使用即整批拒绝（失败批次不占键）
            for (ParsedInstruction instruction : parsed) {
                if (repository.existsSettlementInstructionKey(instruction.instructionKey())) {
                    throw ApiException.conflict("INSTRUCTION_KEY_REUSED",
                            "instructionKey 已被其他清算批次使用: " + instruction.instructionKey());
                }
            }

            // 按主体求净变化
            Map<String, BigDecimal> netChanges = new TreeMap<>();
            for (String key : involved) {
                netChanges.put(key, BigDecimal.ZERO);
            }
            for (ParsedInstruction instruction : parsed) {
                netChanges.merge(instruction.from(), instruction.volume().negate(), BigDecimal::add);
                netChanges.merge(instruction.to(), instruction.volume(), BigDecimal::add);
            }

            // 一致视图下裁决：任一转出方清算后可用额度为负 -> 422
            for (Map.Entry<String, BigDecimal> entry : netChanges.entrySet()) {
                BigDecimal after = locked.get(entry.getKey()).heldAmount().add(entry.getValue());
                if (after.signum() < 0) {
                    throw ApiException.quotaExceeded("主体 " + entry.getKey() + " 清算后可用额度为 "
                            + fmt(after) + "，持有额度不足");
                }
            }
            BigDecimal netSum = netChanges.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (netSum.signum() != 0) {
                throw new IllegalStateException("净额之和必须为 0，实际: " + netSum.toPlainString());
            }

            long now = nowNanos();
            long batchId;
            try {
                batchId = repository.insertSettlementBatch(settlementKey, requestId, commandKey, windowId,
                        parsed.size(), now);
            } catch (DuplicateKeyException e) {
                // 并发：不同 commandKey 但 requestId/settlementKey 撞唯一约束
                SettlementBatchRow concurrent = repository.findSettlementByRequestId(requestId);
                if (concurrent != null) {
                    CommandRow priorCommand = repository.findCommand(concurrent.commandKey());
                    if (priorCommand != null && params.equals(priorCommand.params())) {
                        return toSettlementResponse(concurrent);
                    }
                }
                if (repository.findSettlementByKey(settlementKey) != null) {
                    throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                            "settlementKey 已被使用: " + settlementKey);
                }
                throw ApiException.conflict("SETTLEMENT_CONFLICT", "清算批次并发冲突，请重试");
            }

            // 一次更新全部涉及主体：非零净额增减余额，净额为 0 仅版本加一；所有主体版本均加一
            for (Map.Entry<String, BigDecimal> entry : netChanges.entrySet()) {
                AllocationRow row = locked.get(entry.getKey());
                BigDecimal net = entry.getValue();
                if (net.signum() == 0) {
                    repository.incrementAllocationVersion(row.id(), now);
                } else {
                    repository.applySettlementNetChange(row.id(), net, now);
                }
            }

            // 不可变快照：原指令输入顺序 + 主体净额与前后余额/版本
            for (ParsedInstruction instruction : parsed) {
                try {
                    repository.insertSettlementInstruction(batchId, instruction.seqNo(),
                            instruction.instructionKey(), instruction.from(), instruction.to(),
                            instruction.volume(), now);
                } catch (DuplicateKeyException e) {
                    throw ApiException.conflict("INSTRUCTION_KEY_REUSED",
                            "instructionKey 已被其他清算批次使用: " + instruction.instructionKey());
                }
            }
            for (Map.Entry<String, BigDecimal> entry : netChanges.entrySet()) {
                AllocationRow row = locked.get(entry.getKey());
                BigDecimal balanceAfter = row.heldAmount().add(entry.getValue());
                repository.insertSettlementSnapshot(batchId, entry.getKey(), entry.getValue(),
                        row.heldAmount(), balanceAfter, row.version(), row.version() + 1);
            }

            return toSettlementResponse(repository.findSettlementByKey(settlementKey));
        });
    }

    /** 查询清算批次详情（只读）：批次头 + 原指令（输入顺序）+ 全部主体净额快照。 */
    public SettlementResponse getSettlement(String settlementKey) {
        requireKey("settlementKey", settlementKey);
        SettlementBatchRow batch = repository.findSettlementByKey(settlementKey);
        if (batch == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "清算批次不存在: " + settlementKey);
        }
        return toSettlementResponse(batch);
    }

    /** 按主体查询其参与过的全部清算快照（只读，按批次提交顺序）。 */
    public SubjectSettlementHistoryResponse getSubjectSettlementHistory(String allocationKey) {
        requireKey("allocationKey", allocationKey);
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SubjectSnapshotView> snapshots = repository.listSubjectSettlementHistory(allocationKey).stream()
                .map(this::toSnapshotView).toList();
        return new SubjectSettlementHistoryResponse(allocationKey, snapshots);
    }

    private SettlementResponse toSettlementResponse(SettlementBatchRow batch) {
        List<SettlementInstructionView> instructions = repository.listSettlementInstructions(batch.id())
                .stream().map(row -> new SettlementInstructionView(row.seqNo(), row.instructionKey(),
                        row.fromAllocationKey(), row.toAllocationKey(), fmt(row.volume()))).toList();
        List<SubjectSnapshotView> subjects = repository.listSettlementSnapshots(batch.id()).stream()
                .map(this::toSnapshotView).toList();
        return new SettlementResponse(batch.settlementKey(), batch.requestId(), batch.windowId(),
                batch.instructionCount(), instructions, subjects, toIso(batch.createdNanos()));
    }

    private SubjectSnapshotView toSnapshotView(SettlementSnapshotRow row) {
        return new SubjectSnapshotView(row.allocationKey(), fmt(row.netChange()), fmt(row.balanceBefore()),
                fmt(row.balanceAfter()), row.versionBefore(), row.versionAfter());
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
                fmt(row.heldAmount()), row.requester(), row.status(), row.version(),
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
