package com.example.starter.water;

import com.example.starter.water.WaterRepository.AllocationRow;
import com.example.starter.water.WaterRepository.ChannelRow;
import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.RiskRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.BatchSettleItem;
import com.example.starter.water.dto.Dtos.BatchSettleResponse;
import com.example.starter.water.dto.Dtos.ChannelResponse;
import com.example.starter.water.dto.Dtos.ImpactedAllocation;
import com.example.starter.water.dto.Dtos.OutageImpactResponse;
import com.example.starter.water.dto.Dtos.OutageListResponse;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.RiskItem;
import com.example.starter.water.dto.Dtos.RiskListResponse;
import com.example.starter.water.dto.Dtos.SettlementListResponse;
import com.example.starter.water.dto.Dtos.SettlementRejectionResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.TreeSet;

import static com.example.starter.water.WaterService.STATUS_APPROVED;
import static com.example.starter.water.WaterService.fmt;
import static com.example.starter.water.WaterService.parseAmount;
import static com.example.starter.water.WaterService.parseInstant;
import static com.example.starter.water.WaterService.requireKey;
import static com.example.starter.water.WaterService.toIso;

/**
 * 输水渠停运窗口与配额核销联动服务。
 *
 * <p>停运窗口：UTC 左闭右开，同渠道不得重叠（端点相接合法）；所有渠道变更携带 expectedVersion，
 * 在渠道行锁内校验，版本不符返回 409。已开始窗口不可删除，只能记录不早于当前时刻的提前恢复，
 * 恢复仅影响之后的核销（生效区间被截断为 [start, recovered)）。</p>
 *
 * <p>核销联动：单笔/批量核销与转让结算时，若申请在停运受影响集合内且其供水窗口与生效停运区间
 * 相交即 422；批量核销先按最终渠道容量、申请余额和停运后态预校验，任一失败全部回滚。
 * 下达停运时已批准未结算申请写入不可变供应风险，风险申请不能作为转出方再次转让。</p>
 *
 * <p>串行化：渠道变更、停运下达/删除/恢复、核销与窗口内操作按事务提交顺序裁决；
 * 全局锁顺序为 窗口行（按 ID 升序）→ 渠道行 → 申请行（按业务键升序），避免死锁。</p>
 */
@Service
public class CanalService {

    static final String OUTAGE_SCHEDULED = "SCHEDULED";
    static final String OUTAGE_RECOVERED = "RECOVERED";
    static final String OUTAGE_DELETED = "DELETED";

    private final WaterRepository repository;
    private final CommandExecutor commands;
    private final SystemTime time;

    public CanalService(WaterRepository repository, CommandExecutor commands, SystemTime time) {
        this.repository = repository;
        this.commands = commands;
        this.time = time;
    }

    // ------------------------------------------------------------------
    // 渠道
    // ------------------------------------------------------------------

    /** 渠道变更：设置核销容量上限（null 表示不限），携带 expectedVersion 乐观并发校验。 */
    public ChannelResponse changeChannel(String commandKey, String channelId, String capacity,
                                         Integer expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("channelId", channelId);
        BigDecimal cap = capacity == null ? null : parseAmount("capacity", capacity);
        int version = requireVersion(expectedVersion);
        String params = "CHANNEL_CHANGE|" + channelId + "|" + (cap == null ? "null" : cap.toPlainString())
                + "|" + version;
        return commands.run("CHANNEL_CHANGE", commandKey, params, ChannelResponse.class, () -> {
            ChannelRow channel = repository.lockChannel(channelId);
            if (channel == null) {
                throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
            }
            requireChannelVersion(channel, version);
            repository.updateChannel(channelId, cap);
            return toChannelResponse(repository.findChannel(channelId));
        });
    }

    /** 查询渠道。 */
    public ChannelResponse getChannel(String channelId) {
        ChannelRow channel = repository.findChannel(channelId);
        if (channel == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
        }
        return toChannelResponse(channel);
    }

    // ------------------------------------------------------------------
    // 停运窗口
    // ------------------------------------------------------------------

    /**
     * 下达停运窗口：同渠道时段不得重叠；受影响申请集合规范化（去重排序）后构成 outageKey 指纹；
     * 已批准未结算申请写入不可变供应风险；成功后渠道版本加 1。
     */
    public OutageResponse createOutage(String commandKey, String outageKey, String channelId,
                                       String startUtc, String endUtc, List<String> affectedAllocationKeys,
                                       Integer expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("outageKey", outageKey);
        requireKey("channelId", channelId);
        long startNanos = parseInstant("startUtc", startUtc);
        long endNanos = parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        int version = requireVersion(expectedVersion);
        List<String> affected = normalizeAffected(affectedAllocationKeys);
        String params = "OUTAGE_CREATE|" + outageKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + version + "|" + String.join(",", affected);
        return commands.run("OUTAGE_CREATE", commandKey, params, OutageResponse.class, () -> {
            ChannelRow channel = repository.lockChannel(channelId);
            if (channel == null) {
                throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
            }
            // outageKey 业务键重放：同键同指纹（渠道、时段、规范化受影响集合）返回首次结果
            OutageRow existing = repository.findOutageByKey(outageKey);
            if (existing != null) {
                return replayOutage(existing, channelId, startNanos, endNanos, affected);
            }
            requireChannelVersion(channel, version);
            if (repository.existsOverlappingOutage(channelId, startNanos, endNanos)) {
                throw ApiException.conflict("OUTAGE_OVERLAP", "同一渠道存在时间重叠的停运窗口");
            }
            for (String allocationKey : affected) {
                if (repository.findAllocationByKey(allocationKey) == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND", "受影响申请不存在: " + allocationKey);
                }
            }
            long now = time.nowNanos();
            long id;
            try {
                id = repository.insertOutage(outageKey, channelId, startNanos, endNanos, now);
            } catch (DuplicateKeyException e) {
                // 并发同 outageKey：按提交顺序以先提交者为准，同指纹重放、异指纹 409
                OutageRow committed = repository.findOutageByKey(outageKey);
                if (committed == null) {
                    throw ApiException.conflict("OUTAGE_CONFLICT", "相同 outageKey 的停运正在下达，请重试");
                }
                return replayOutage(committed, channelId, startNanos, endNanos, affected);
            }
            repository.insertOutageAllocations(id, affected);
            // 已批准未结算申请写入不可变供应风险；风险申请不能再次转让，但不撤销其既有额度
            for (AllocationRow allocation : repository.listApprovedUnsettledAllocations(channelId)) {
                repository.insertRisk(id, allocation.allocationKey(), now);
            }
            repository.bumpChannelVersion(channelId);
            return toOutageResponse(repository.findOutageById(id));
        });
    }

    /** 删除停运窗口：仅未开始（当前时刻早于开始时刻）可删；删除后不再参与任何拦截。 */
    public OutageResponse deleteOutage(String commandKey, String outageKey, Integer expectedVersion) {
        requireKey("commandKey", commandKey);
        requireKey("outageKey", outageKey);
        int version = requireVersion(expectedVersion);
        String params = "OUTAGE_DELETE|" + outageKey + "|" + version;
        return commands.run("OUTAGE_DELETE", commandKey, params, OutageResponse.class, () -> {
            OutageRow outage = repository.findOutageByKey(outageKey);
            if (outage == null) {
                throw ApiException.notFound("OUTAGE_NOT_FOUND", "停运窗口不存在: " + outageKey);
            }
            ChannelRow channel = lockChannelOf(outage);
            requireChannelVersion(channel, version);
            outage = repository.lockOutageByKey(outageKey);
            if (OUTAGE_DELETED.equals(outage.status())) {
                throw ApiException.conflict("OUTAGE_ALREADY_DELETED", "停运窗口已删除，不能重复删除");
            }
            if (outage.startNanos() <= time.nowNanos()) {
                throw ApiException.conflict("OUTAGE_ALREADY_STARTED",
                        "已开始的停运窗口不可删除，只能记录提前恢复");
            }
            repository.deleteOutageAllocations(outage.id());
            repository.deleteOutage(outage.id());
            repository.bumpChannelVersion(outage.channelId());
            return toOutageResponse(repository.findOutageById(outage.id()));
        });
    }

    /**
     * 记录提前恢复时刻（窗口关闭）：不得早于当前时刻，且必须早于停运结束时刻；
     * 恢复仅影响之后的核销，生效区间截断为 [start, recovered)。
     */
    public OutageResponse recoverOutage(String commandKey, String outageKey, Integer expectedVersion,
                                        String recoveredAtUtc) {
        requireKey("commandKey", commandKey);
        requireKey("outageKey", outageKey);
        int version = requireVersion(expectedVersion);
        long recovered = parseInstant("recoveredAtUtc", recoveredAtUtc);
        String params = "OUTAGE_RECOVER|" + outageKey + "|" + version + "|" + recovered;
        return commands.run("OUTAGE_RECOVER", commandKey, params, OutageResponse.class, () -> {
            OutageRow outage = repository.findOutageByKey(outageKey);
            if (outage == null) {
                throw ApiException.notFound("OUTAGE_NOT_FOUND", "停运窗口不存在: " + outageKey);
            }
            ChannelRow channel = lockChannelOf(outage);
            requireChannelVersion(channel, version);
            outage = repository.lockOutageByKey(outageKey);
            if (OUTAGE_DELETED.equals(outage.status())) {
                throw ApiException.conflict("OUTAGE_ALREADY_DELETED", "已删除的停运窗口不能恢复");
            }
            if (OUTAGE_RECOVERED.equals(outage.status())) {
                throw ApiException.conflict("OUTAGE_ALREADY_RECOVERED", "停运窗口已记录提前恢复");
            }
            long now = time.nowNanos();
            if (outage.startNanos() > now) {
                throw ApiException.conflict("OUTAGE_NOT_STARTED",
                        "尚未开始的停运窗口只能删除，不能记录提前恢复");
            }
            if (outage.endNanos() <= now) {
                throw ApiException.conflict("OUTAGE_ALREADY_ENDED", "停运窗口已结束，无需恢复");
            }
            if (recovered < now) {
                throw ApiException.badRequest("RECOVERY_IN_PAST", "恢复时刻不得早于当前时刻");
            }
            if (recovered >= outage.endNanos()) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "恢复时刻必须早于停运结束时刻");
            }
            repository.recoverOutage(outage.id(), recovered);
            repository.bumpChannelVersion(outage.channelId());
            return toOutageResponse(repository.findOutageById(outage.id()));
        });
    }

    /** 查询渠道全部停运窗口（含已删除），按下达顺序返回。 */
    public OutageListResponse listOutages(String channelId) {
        if (repository.findChannel(channelId) == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
        }
        List<OutageResponse> outages = repository.listOutagesByChannel(channelId).stream()
                .map(this::toOutageResponse).toList();
        return new OutageListResponse(channelId, outages);
    }

    /** 查询停运影响：受影响申请及其供水窗口与生效停运区间的相交情况。 */
    public OutageImpactResponse getOutageImpact(String outageKey) {
        OutageRow outage = repository.findOutageByKey(outageKey);
        if (outage == null) {
            throw ApiException.notFound("OUTAGE_NOT_FOUND", "停运窗口不存在: " + outageKey);
        }
        long effectiveEnd = outage.recoveredNanos() != null ? outage.recoveredNanos() : outage.endNanos();
        List<ImpactedAllocation> impacted = new ArrayList<>();
        for (String allocationKey : repository.listOutageAllocations(outage.id())) {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                continue;
            }
            WindowRow window = repository.findWindowById(allocation.windowId());
            boolean intersects = !OUTAGE_DELETED.equals(outage.status())
                    && outage.startNanos() < window.endNanos() && window.startNanos() < effectiveEnd;
            impacted.add(new ImpactedAllocation(allocationKey, window.id(), toIso(window.startNanos()),
                    toIso(window.endNanos()), allocation.status(), fmt(allocation.heldAmount()), intersects));
        }
        return new OutageImpactResponse(outage.outageKey(), outage.channelId(), toIso(outage.startNanos()),
                toIso(outage.endNanos()), outageStatus(outage), impacted);
    }

    /** 查询申请的全部不可变供应风险。 */
    public RiskListResponse getAllocationRisks(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<RiskItem> risks = new ArrayList<>();
        for (RiskRow risk : repository.listRisksByAllocation(allocationKey)) {
            OutageRow outage = repository.findOutageById(risk.outageId());
            risks.add(new RiskItem(outage.outageKey(), outage.channelId(), toIso(risk.createdNanos())));
        }
        return new RiskListResponse(allocationKey, risks);
    }

    // ------------------------------------------------------------------
    // 核销
    // ------------------------------------------------------------------

    /**
     * 单笔核销：从申请持有额度等额扣减并写不可变流水。
     * 申请窗口与任何生效停运窗口相交即 422；余额不足 422；超过渠道最终容量 422。
     */
    public SettlementResponse settle(String commandKey, String settlementKey, String allocationKey,
                                     String amount, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("settlementKey", settlementKey);
        requireKey("allocationKey", allocationKey);
        requireKey("X-Actor-Id", actor);
        BigDecimal qty = parseAmount("amount", amount);
        String params = "SETTLE|" + settlementKey + "|" + allocationKey + "|" + qty.toPlainString()
                + "|" + actor;
        return commands.run("SETTLE", commandKey, params, SettlementResponse.class, () -> {
            AllocationRow allocation = repository.findAllocationByKey(allocationKey);
            if (allocation == null) {
                throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
            }
            WindowRow window = lockWindowOf(allocation);
            ChannelRow channel = lockChannelOf(window);
            allocation = repository.lockAllocationByKey(allocationKey);
            requireSettlable(allocation, window, channel, qty, actor);
            long now = time.nowNanos();
            repository.decrementHeldAmount(allocation.id(), qty, now);
            try {
                repository.insertSettlement(settlementKey, allocationKey, qty, actor, null, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED", "settlementKey 已被使用: " + settlementKey);
            }
            return toSettlementResponse(repository.findSettlementByKey(settlementKey));
        });
    }

    /**
     * 批量核销：同一渠道的多条申请一次性核销。先按最终渠道容量、申请余额和停运后态预校验，
     * 任一失败则全部水量、余额和流水回滚，不留下半成品状态。
     */
    public BatchSettleResponse settleBatch(String commandKey, List<BatchSettleItem> items, String actor) {
        requireKey("commandKey", commandKey);
        requireKey("X-Actor-Id", actor);
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "items 不能为空");
        }
        List<String> keys = new ArrayList<>();
        List<BigDecimal> amounts = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            BatchSettleItem item = items.get(i);
            if (item == null) {
                throw ApiException.badRequest("INVALID_ARGUMENT", "items[" + i + "] 不能为空");
            }
            requireKey("items[" + i + "].allocationKey", item.allocationKey());
            if (!seen.add(item.allocationKey())) {
                throw ApiException.badRequest("INVALID_ARGUMENT",
                        "批量核销包含重复申请: " + item.allocationKey());
            }
            keys.add(item.allocationKey());
            amounts.add(parseAmount("items[" + i + "].amount", item.amount()));
        }
        StringBuilder params = new StringBuilder("SETTLE_BATCH|").append(actor);
        for (int i = 0; i < keys.size(); i++) {
            params.append('|').append(keys.get(i)).append(':').append(amounts.get(i).toPlainString());
        }
        return commands.run("SETTLE_BATCH", commandKey, params.toString(), BatchSettleResponse.class, () -> {
            List<AllocationRow> allocations = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                AllocationRow allocation = repository.findAllocationByKey(keys.get(i));
                if (allocation == null) {
                    throw ApiException.notFound("ALLOCATION_NOT_FOUND",
                            "配水申请不存在: " + keys.get(i) + "（第 " + (i + 1) + " 项）");
                }
                allocations.add(allocation);
            }
            // 预校验：全部申请必须属于同一渠道（最终渠道容量口径）
            String channelId = null;
            List<Long> windowIds = new ArrayList<>();
            for (AllocationRow allocation : allocations) {
                WindowRow window = repository.findWindowById(allocation.windowId());
                if (channelId == null) {
                    channelId = window.channelId();
                } else if (!channelId.equals(window.channelId())) {
                    throw ApiException.badRequest("DIFFERENT_CHANNEL", "批量核销的申请必须属于同一渠道");
                }
                if (!windowIds.contains(window.id())) {
                    windowIds.add(window.id());
                }
            }
            // 锁顺序：窗口行（按 ID 升序）→ 渠道行 → 申请行（按业务键升序）
            windowIds.sort(Long::compareTo);
            List<WindowRow> windows = new ArrayList<>();
            for (long windowId : windowIds) {
                WindowRow window = repository.lockWindowById(windowId);
                if (window == null) {
                    throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
                }
                windows.add(window);
            }
            ChannelRow channel = lockChannelOf(channelId);
            List<String> sortedKeys = new ArrayList<>(keys);
            sortedKeys.sort(String::compareTo);
            List<AllocationRow> locked = new ArrayList<>();
            for (String key : sortedKeys) {
                locked.add(repository.lockAllocationByKey(key));
            }
            List<AllocationRow> lockedByItem = new ArrayList<>();
            for (String key : keys) {
                lockedByItem.add(locked.get(sortedKeys.indexOf(key)));
            }
            // 预校验：申请余额、归属与停运后态（任一失败整体回滚）；
            // 批量核销由操作人统一发起，逐项不做申请人本人校验，actor 仅写入流水。
            // 最终渠道容量按逐项累计口径校验（已核销 + 本批前 i 项 + 本项），任何一项超额即整体回滚。
            BigDecimal batchTotal = BigDecimal.ZERO;
            for (int i = 0; i < keys.size(); i++) {
                AllocationRow allocation = lockedByItem.get(i);
                WindowRow window = windows.get(windowIds.indexOf(allocation.windowId()));
                requireSettlable(allocation, window, channel, amounts.get(i), null);
                batchTotal = batchTotal.add(amounts.get(i));
                requireChannelCapacity(channel, batchTotal);
            }
            long now = time.nowNanos();
            for (int i = 0; i < keys.size(); i++) {
                AllocationRow allocation = lockedByItem.get(i);
                repository.decrementHeldAmount(allocation.id(), amounts.get(i), now);
                repository.insertSettlement(commandKey + "#" + (i + 1), keys.get(i), amounts.get(i),
                        actor, commandKey, now);
            }
            List<SettlementResponse> settlements = repository.listSettlementsByBatch(commandKey).stream()
                    .map(this::toSettlementResponse).toList();
            return new BatchSettleResponse(commandKey, settlements);
        });
    }

    /** 查询申请的全部核销流水。 */
    public SettlementListResponse getSettlements(String allocationKey) {
        if (repository.findAllocationByKey(allocationKey) == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        List<SettlementResponse> settlements = repository.listSettlementsByAllocation(allocationKey).stream()
                .map(this::toSettlementResponse).toList();
        return new SettlementListResponse(allocationKey, settlements);
    }

    /**
     * 查询核销拒绝原因（只读预览，不产生任何状态变更）：
     * 按与真实核销相同的顺序检查余额、停运后态与最终渠道容量，返回第一个可区分的拒绝原因。
     */
    public SettlementRejectionResponse previewSettlement(String allocationKey, String amount) {
        AllocationRow allocation = repository.findAllocationByKey(allocationKey);
        if (allocation == null) {
            throw ApiException.notFound("ALLOCATION_NOT_FOUND", "配水申请不存在: " + allocationKey);
        }
        BigDecimal qty = parseAmount("amount", amount);
        WindowRow window = repository.findWindowById(allocation.windowId());
        ChannelRow channel = repository.findChannel(window.channelId());
        try {
            requireSettlable(allocation, window, channel, qty, null);
        } catch (ApiException e) {
            return new SettlementRejectionResponse(allocationKey, fmt(qty), false, e.code(), e.getMessage());
        }
        return new SettlementRejectionResponse(allocationKey, fmt(qty), true, null, null);
    }

    // ------------------------------------------------------------------
    // 转让门禁（供 WaterService 转让结算调用）
    // ------------------------------------------------------------------

    /**
     * 转让结算门禁：在窗口锁与申请锁内调用；渠道行在此加锁，与停运下达/恢复按提交顺序串行裁决。
     * 源申请存在供应风险 -> 409；源或目标申请窗口与生效停运窗口相交 -> 422。
     */
    void requireTransferAllowed(AllocationRow source, AllocationRow target, WindowRow window) {
        if (repository.existsRiskForAllocation(source.allocationKey())) {
            throw ApiException.conflict("SUPPLY_RISK_BLOCKED",
                    "申请 " + source.allocationKey() + " 存在供应风险，不能再次转让");
        }
        ChannelRow channel = lockChannelOf(window);
        requireNoOutageConflict(channel, window, source.allocationKey());
        requireNoOutageConflict(channel, window, target.allocationKey());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 核销预校验：归属、状态、申请余额、停运后态与最终渠道容量，任一不满足抛出可区分原因。 */
    private void requireSettlable(AllocationRow allocation, WindowRow window, ChannelRow channel,
                                  BigDecimal amount, String actor) {
        if (actor != null && !allocation.requester().equals(actor)) {
            throw ApiException.conflict("NOT_OWNER", "只有申请人本人可以核销该申请");
        }
        if (!STATUS_APPROVED.equals(allocation.status())) {
            throw ApiException.conflict("ALLOCATION_NOT_SETTLABLE",
                    "仅已批准的申请可以核销，当前状态: " + allocation.status());
        }
        if (allocation.heldAmount().compareTo(amount) < 0) {
            throw ApiException.quotaExceeded("INSUFFICIENT_BALANCE",
                    "申请 " + allocation.allocationKey() + " 持有额度 " + fmt(allocation.heldAmount())
                            + " 不足，无法核销 " + fmt(amount));
        }
        requireNoOutageConflict(channel, window, allocation.allocationKey());
        requireChannelCapacity(channel, amount);
    }

    /** 停运后态校验：申请在受影响集合内且其供水窗口与生效停运区间相交即 422。 */
    private void requireNoOutageConflict(ChannelRow channel, WindowRow window, String allocationKey) {
        if (channel == null) {
            return;
        }
        List<OutageRow> conflicts = repository.findConflictingOutages(channel.channelId(), allocationKey,
                window.startNanos(), window.endNanos());
        if (!conflicts.isEmpty()) {
            OutageRow first = conflicts.get(0);
            throw ApiException.quotaExceeded("OUTAGE_WINDOW_CONFLICT",
                    "申请 " + allocationKey + " 的供水窗口与生效停运窗口 " + first.outageKey()
                            + " 相交，禁止核销或转让结算");
        }
    }

    /** 最终渠道容量校验：渠道容量有限时，累计已核销 + 本次核销不得超过容量。 */
    private void requireChannelCapacity(ChannelRow channel, BigDecimal additional) {
        if (channel == null || channel.capacity() == null) {
            return;
        }
        BigDecimal settled = repository.sumSettledByChannel(channel.channelId());
        if (settled.add(additional).compareTo(channel.capacity()) > 0) {
            throw ApiException.quotaExceeded("CHANNEL_CAPACITY_EXCEEDED",
                    "渠道 " + channel.channelId() + " 最终容量 " + fmt(channel.capacity())
                            + " 不足：已核销 " + fmt(settled) + "，本次核销 " + fmt(additional));
        }
    }

    private WindowRow lockWindowOf(AllocationRow allocation) {
        WindowRow window = repository.lockWindowById(allocation.windowId());
        if (window == null) {
            throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + allocation.windowId());
        }
        return window;
    }

    private ChannelRow lockChannelOf(WindowRow window) {
        return lockChannelOf(window.channelId());
    }

    private ChannelRow lockChannelOf(OutageRow outage) {
        return lockChannelOf(outage.channelId());
    }

    private ChannelRow lockChannelOf(String channelId) {
        ChannelRow channel = repository.lockChannel(channelId);
        if (channel == null) {
            throw ApiException.notFound("CHANNEL_NOT_FOUND", "渠道不存在: " + channelId);
        }
        return channel;
    }

    private static int requireVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "expectedVersion 不能为空");
        }
        return expectedVersion;
    }

    private static void requireChannelVersion(ChannelRow channel, int expectedVersion) {
        if (channel.version() != expectedVersion) {
            throw ApiException.conflict("CHANNEL_VERSION_MISMATCH",
                    "渠道 " + channel.channelId() + " 当前版本 " + channel.version()
                            + " 与 expectedVersion " + expectedVersion + " 不一致");
        }
    }

    /** 规范化受影响申请集合：逐项校验、去重、字典序排序，构成 outageKey 指纹的一部分。 */
    private static List<String> normalizeAffected(List<String> affectedAllocationKeys) {
        if (affectedAllocationKeys == null) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        for (int i = 0; i < affectedAllocationKeys.size(); i++) {
            String key = affectedAllocationKeys.get(i);
            requireKey("affectedAllocationKeys[" + i + "]", key);
            normalized.add(key);
        }
        return List.copyOf(normalized);
    }

    /**
     * outageKey 同键重放：已存在的停运与本次请求指纹（渠道、UTC 时段、规范化受影响集合）
     * 完全一致时返回首次结果，否则 409。已删除的停运不参与重放，同键视为被占用。
     */
    private OutageResponse replayOutage(OutageRow existing, String channelId, long startNanos,
                                        long endNanos, List<String> affected) {
        boolean sameFingerprint = existing.channelId().equals(channelId)
                && existing.startNanos() == startNanos && existing.endNanos() == endNanos
                && repository.listOutageAllocations(existing.id()).equals(affected)
                && !OUTAGE_DELETED.equals(existing.status());
        if (!sameFingerprint) {
            throw ApiException.conflict("OUTAGE_KEY_REUSED",
                    "outageKey 已被使用且指纹不一致: " + existing.outageKey());
        }
        return toOutageResponse(existing);
    }

    private String outageStatus(OutageRow row) {
        if (OUTAGE_DELETED.equals(row.status())) {
            return "DELETED";
        }
        if (OUTAGE_RECOVERED.equals(row.status())) {
            return "RECOVERED";
        }
        long now = time.nowNanos();
        if (now < row.startNanos()) {
            return "SCHEDULED";
        }
        if (now < row.endNanos()) {
            return "ACTIVE";
        }
        return "ENDED";
    }

    private ChannelResponse toChannelResponse(ChannelRow row) {
        return new ChannelResponse(row.channelId(),
                row.capacity() == null ? null : fmt(row.capacity()), row.version(),
                toIso(row.createdNanos()));
    }

    private OutageResponse toOutageResponse(OutageRow row) {
        return new OutageResponse(row.id(), row.outageKey(), row.channelId(), toIso(row.startNanos()),
                toIso(row.endNanos()), outageStatus(row),
                row.recoveredNanos() == null ? null : toIso(row.recoveredNanos()),
                repository.listOutageAllocations(row.id()), toIso(row.createdNanos()));
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.settlementKey(), row.allocationKey(), fmt(row.amount()),
                row.actor(), row.batchKey(), toIso(row.createdNanos()));
    }
}
