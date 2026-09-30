package com.example.starter.water;

import com.example.starter.water.WaterRepository.OutageRow;
import com.example.starter.water.WaterRepository.PostponementRow;
import com.example.starter.water.WaterRepository.ScheduleRow;
import com.example.starter.water.WaterRepository.SettlementRow;
import com.example.starter.water.WaterRepository.WindowRow;
import com.example.starter.water.dto.Dtos.AffectedSchedule;
import com.example.starter.water.dto.Dtos.OutageResponse;
import com.example.starter.water.dto.Dtos.PostponementItem;
import com.example.starter.water.dto.Dtos.ScheduleResponse;
import com.example.starter.water.dto.Dtos.SettlementResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 灌渠中断与排班顺延业务服务。
 *
 * <p>串行化：同一渠道的中断登记与排班发布在各自事务内先对 channel_mutex 渠道行
 * SELECT ... FOR UPDATE，按事务提交顺序串行，二者竞争后不会留下时间交叉的占用。</p>
 *
 * <p>顺延整批裁决：受影响（与中断窗口按 UTC 左闭右开交叉且未开始）的排班统一顺延同一分钟数，
 * 顺延后仍须落在各自配水窗口 [start,end) 内，且不得与渠道上其他占用（含已核销排班）重叠；
 * 任一排班已开始、越界或冲突，整笔事务回滚，排班、中断状态与额度保持原样，相邻端点不算重叠。</p>
 *
 * <p>幂等：登记中断的 requestId 即 commandKey，同键同参重放返回首次受影响排班与新窗口，
 * 同键换顺延分钟数（或改其他参数）返回 409。</p>
 */
@Service
public class OutageService {

    static final String SCHEDULE_COMPLETED = "COMPLETED";

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;
    private static final int MAX_REASON_LENGTH = 512;

    private final WaterRepository repository;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    /** 可控时钟：null 表示系统 UTC 时钟；测试可固定到某一时刻。 */
    private volatile Long fixedNowNanos;

    public OutageService(WaterRepository repository, PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /** 固定业务时钟（仅测试用），传 null 恢复系统时钟。 */
    void setFixedNowNanos(Long nowNanos) {
        this.fixedNowNanos = nowNanos;
    }

    /** 固定业务时钟到 ISO-8601 UTC 时刻（仅测试用）。 */
    void setFixedNow(String utc) {
        setFixedNowNanos(utc == null ? null : WaterService.parseInstant("fixedNow", utc));
    }

    private long nowNanos() {
        Long fixed = fixedNowNanos;
        return fixed != null ? fixed : WaterService.nowNanos();
    }

    // ------------------------------------------------------------------
    // 排班发布
    // ------------------------------------------------------------------

    /**
     * 发布排班：窗口取自所属配水窗口（渠道随之确定），排班必须完整落在配水窗口内，
     * 且不得与同渠道既有占用交叉（左闭右开，相邻合法）。
     */
    public ScheduleResponse publishSchedule(String commandKey, String scheduleKey, Long windowId,
                                            String startUtc, String endUtc) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("scheduleKey", scheduleKey);
        if (windowId == null) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "windowId 不能为空");
        }
        long startNanos = WaterService.parseInstant("startUtc", startUtc);
        long endNanos = WaterService.parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        String params = "SCHEDULE_PUBLISH|" + scheduleKey + "|" + windowId + "|" + startNanos + "|" + endNanos;
        return runCommand("SCHEDULE_PUBLISH", commandKey, params, ScheduleResponse.class, () -> {
            WindowRow window = repository.findWindowById(windowId);
            if (window == null) {
                throw ApiException.notFound("WINDOW_NOT_FOUND", "供水窗口不存在: " + windowId);
            }
            long now = nowNanos();
            // 渠道行锁：与同渠道中断登记按提交顺序串行
            repository.lockChannel(window.channelId(), now);
            if (!repository.listOverlappingOutages(window.channelId(), startNanos, endNanos).isEmpty()) {
                throw ApiException.conflict("OUTAGE_WINDOW_CONFLICT",
                        "排班与已登记灌渠中断窗口交叉，不能发布；相邻端点允许");
            }
            if (startNanos < window.startNanos() || endNanos > window.endNanos()) {
                throw ApiException.unprocessable("SCHEDULE_OUT_OF_WINDOW",
                        "排班必须完整落在配水窗口内: " + WaterService.toIso(window.startNanos()) + "/"
                                + WaterService.toIso(window.endNanos()));
            }
            if (!repository.listOverlappingSchedules(window.channelId(), startNanos, endNanos).isEmpty()) {
                throw ApiException.conflict("SCHEDULE_OVERLAP", "排班与同渠道其他占用时间交叉，相邻端点允许");
            }
            try {
                long id = repository.insertSchedule(scheduleKey, windowId, window.channelId(),
                        startNanos, endNanos, now);
                return toScheduleResponse(repository.findScheduleById(id));
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("SCHEDULE_KEY_REUSED", "scheduleKey 已被使用: " + scheduleKey);
            }
        });
    }

    // ------------------------------------------------------------------
    // 中断登记与整批顺延
    // ------------------------------------------------------------------

    /**
     * 登记灌渠中断并对所有受影响且未开始的排班统一顺延 postponeMinutes 分钟。
     * 任一排班已开始、顺延越界或与其他占用冲突 -> 422，整批回滚。
     */
    public OutageResponse registerOutage(String commandKey, String outageKey, String channelId,
                                         String startUtc, String endUtc, String reason, Integer postponeMinutes) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("outageKey", outageKey);
        WaterService.requireKey("channelId", channelId);
        long startNanos = WaterService.parseInstant("startUtc", startUtc);
        long endNanos = WaterService.parseInstant("endUtc", endUtc);
        if (startNanos >= endNanos) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "startUtc 必须早于 endUtc");
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "reason 不能为空");
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > MAX_REASON_LENGTH) {
            throw ApiException.badRequest("INVALID_ARGUMENT",
                    "reason 最长 " + MAX_REASON_LENGTH + " 字符");
        }
        if (postponeMinutes == null || postponeMinutes <= 0) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "postponeMinutes 必须为大于 0 的整数分钟");
        }
        String params = "OUTAGE_REGISTER|" + outageKey + "|" + channelId + "|" + startNanos + "|" + endNanos
                + "|" + trimmedReason + "|" + postponeMinutes;
        return runCommand("OUTAGE_REGISTER", commandKey, params, OutageResponse.class, () -> {
            long now = nowNanos();
            // 渠道行锁：与同渠道排班发布互斥，杜绝竞争后交叉占用
            repository.lockChannel(channelId, now);
            long outageId;
            try {
                outageId = repository.insertOutage(outageKey, channelId, startNanos, endNanos,
                        trimmedReason, postponeMinutes, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("OUTAGE_KEY_REUSED", "outageKey/requestId 已被使用: " + outageKey);
            }

            List<ScheduleRow> overlapping =
                    repository.lockOverlappingSchedules(channelId, startNanos, endNanos);
            List<ScheduleRow> affected = new ArrayList<>();
            for (ScheduleRow schedule : overlapping) {
                if (SCHEDULE_COMPLETED.equals(schedule.status())) {
                    throw ApiException.unprocessable("OUTAGE_OVERLAPS_COMPLETED",
                            "排班已核销，不能因中断顺延: " + schedule.scheduleKey());
                }
                // 未开始：当前起点严格晚于登记时刻；起点已到即视为已开始
                if (schedule.startNanos() <= now) {
                    throw ApiException.unprocessable("SCHEDULE_STARTED",
                            "受影响排班已开始，不能顺延: " + schedule.scheduleKey());
                }
                affected.add(schedule);
            }

            long delta = Math.multiplyExact((long) postponeMinutes, NANOS_PER_MINUTE);
            // 渠道全部占用与已登记中断作为顺延裁决集合（含本次中断、其他窗口与已核销排班）
            List<ScheduleRow> occupants = repository.listSchedulesByChannel(channelId);
            List<OutageRow> outages = repository.listOutagesByChannel(channelId);
            Set<Long> affectedIds = affected.stream().map(ScheduleRow::id).collect(Collectors.toSet());
            List<Move> moves = new ArrayList<>();
            for (ScheduleRow schedule : affected) {
                WindowRow window = repository.findWindowById(schedule.windowId());
                long newStart = Math.addExact(schedule.startNanos(), delta);
                long newEnd = Math.addExact(schedule.endNanos(), delta);
                if (window == null || newStart < window.startNanos() || newEnd > window.endNanos()) {
                    throw ApiException.unprocessable("POSTPONE_OUT_OF_WINDOW",
                            "排班 " + schedule.scheduleKey() + " 顺延后超出配水窗口，整批拒绝");
                }
                // 新窗口仍落在任一已登记中断窗口（含本次）内 -> 整批拒绝，相邻合法
                for (OutageRow outage : outages) {
                    if (newStart < outage.endNanos() && outage.startNanos() < newEnd) {
                        throw ApiException.unprocessable("POSTPONE_STILL_IN_OUTAGE",
                                "排班 " + schedule.scheduleKey() + " 顺延后仍处于中断窗口内，整批拒绝");
                    }
                }
                // 与不参与本次顺延的既有占用（当前窗口）冲突检测
                for (ScheduleRow other : occupants) {
                    if (affectedIds.contains(other.id())) {
                        continue;
                    }
                    if (newStart < other.endNanos() && other.startNanos() < newEnd) {
                        throw ApiException.unprocessable("POSTPONE_CONFLICT",
                                "排班 " + schedule.scheduleKey() + " 顺延后与其他占用交叉，整批拒绝");
                    }
                }
                // 与本批其他排班顺延后的新窗口冲突检测（统一平移下相对关系不变，此处显式兜底）
                for (Move planned : moves) {
                    if (newStart < planned.newEnd && planned.newStart < newEnd) {
                        throw ApiException.unprocessable("POSTPONE_CONFLICT",
                                "排班 " + schedule.scheduleKey() + " 顺延后与同批排班交叉，整批拒绝");
                    }
                }
                moves.add(new Move(schedule, newStart, newEnd));
            }

            // 全部校验通过后整批落库
            for (Move move : moves) {
                ScheduleRow s = move.schedule;
                repository.moveSchedule(s.id(), move.newStart, move.newEnd, now);
                repository.insertPostponement(s.id(), outageId, outageKey, trimmedReason, postponeMinutes,
                        s.originalStartNanos(), s.originalEndNanos(),
                        s.startNanos(), s.endNanos(), move.newStart, move.newEnd, now);
            }
            return toOutageResponse(repository.findOutageById(outageId));
        });
    }

    /** 查询中断及首次登记时的受影响排班与顺延对应关系；不存在返回 404。 */
    public OutageResponse getOutage(String outageKey) {
        WaterService.requireKey("outageKey", outageKey);
        OutageRow outage = repository.findOutageByKey(outageKey);
        if (outage == null) {
            throw ApiException.notFound("OUTAGE_NOT_FOUND", "灌渠中断不存在: " + outageKey);
        }
        return toOutageResponse(outage);
    }

    // ------------------------------------------------------------------
    // 排班查询
    // ------------------------------------------------------------------

    /** 查询排班：原始窗口、当前窗口、是否已开始及完整顺延链；不存在返回 404。 */
    public ScheduleResponse getSchedule(String scheduleKey) {
        WaterService.requireKey("scheduleKey", scheduleKey);
        ScheduleRow schedule = repository.findScheduleByKey(scheduleKey);
        if (schedule == null) {
            throw ApiException.notFound("SCHEDULE_NOT_FOUND", "排班不存在: " + scheduleKey);
        }
        return toScheduleResponse(schedule);
    }

    // ------------------------------------------------------------------
    // 历史用水核销
    // ------------------------------------------------------------------

    /**
     * 历史用水核销：对排班当前窗口做不可变快照并把排班置为 COMPLETED。
     * 快照引用核销当时的排班，之后排班再被顺延也不改写核销记录。
     */
    public SettlementResponse settleUsage(String commandKey, String settlementKey, String scheduleKey,
                                          String volume) {
        WaterService.requireKey("commandKey", commandKey);
        WaterService.requireKey("settlementKey", settlementKey);
        WaterService.requireKey("scheduleKey", scheduleKey);
        BigDecimal qty = WaterService.parseAmount("volume", volume);
        String params = "USAGE_SETTLE|" + settlementKey + "|" + scheduleKey + "|" + qty.toPlainString();
        return runCommand("USAGE_SETTLE", commandKey, params, SettlementResponse.class, () -> {
            ScheduleRow schedule = repository.lockScheduleByKey(scheduleKey);
            if (schedule == null) {
                throw ApiException.notFound("SCHEDULE_NOT_FOUND", "排班不存在: " + scheduleKey);
            }
            if (SCHEDULE_COMPLETED.equals(schedule.status())) {
                throw ApiException.conflict("SCHEDULE_ALREADY_COMPLETED",
                        "排班已核销，不能重复核销: " + scheduleKey);
            }
            long now = nowNanos();
            if (now < schedule.startNanos()) {
                throw ApiException.unprocessable("USAGE_BEFORE_START",
                        "排班尚未开始，不能核销用水: " + scheduleKey);
            }
            try {
                repository.insertSettlement(settlementKey, schedule.id(), scheduleKey,
                        schedule.startNanos(), schedule.endNanos(), qty, now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("SETTLEMENT_KEY_REUSED",
                        "settlementKey 已被使用: " + settlementKey);
            }
            repository.markScheduleCompleted(schedule.id(), now);
            return toSettlementResponse(repository.findSettlementByKey(settlementKey));
        });
    }

    /** 查询核销记录（当时排班窗口快照）；不存在返回 404。 */
    public SettlementResponse getSettlement(String settlementKey) {
        WaterService.requireKey("settlementKey", settlementKey);
        SettlementRow settlement = repository.findSettlementByKey(settlementKey);
        if (settlement == null) {
            throw ApiException.notFound("SETTLEMENT_NOT_FOUND", "用水核销不存在: " + settlementKey);
        }
        return toSettlementResponse(settlement);
    }

    // ------------------------------------------------------------------
    // 幂等命令框架（与 WaterService 同口径）
    // ------------------------------------------------------------------

    private <T> T runCommand(String operation, String commandKey, String params, Class<T> type,
                             Supplier<T> business) {
        try {
            return tx.execute(status -> {
                WaterRepository.CommandRow existing = repository.findCommand(commandKey);
                if (existing != null) {
                    return replay(existing, operation, params, type);
                }
                repository.insertCommand(commandKey, operation, params, nowNanos());
                T result = business.get();
                repository.updateCommandResponse(commandKey, toJson(result));
                return result;
            });
        } catch (DuplicateKeyException e) {
            WaterRepository.CommandRow committed = repository.findCommand(commandKey);
            if (committed == null || committed.response() == null) {
                throw ApiException.conflict("COMMAND_CONFLICT", "相同 commandKey 的命令正在处理，请重试");
            }
            return replay(committed, operation, params, type);
        }
    }

    private <T> T replay(WaterRepository.CommandRow existing, String operation, String params, Class<T> type) {
        if (!existing.operation().equals(operation) || !existing.params().equals(params)) {
            throw ApiException.conflict("COMMAND_KEY_REUSED", "相同 commandKey 但参数不同，拒绝重放");
        }
        return fromJson(existing.response(), type);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private record Move(ScheduleRow schedule, long newStart, long newEnd) {
    }

    private ScheduleResponse toScheduleResponse(ScheduleRow row) {
        boolean started = SCHEDULE_COMPLETED.equals(row.status()) || nowNanos() >= row.startNanos();
        List<PostponementItem> items = repository.listPostponements(row.id()).stream()
                .map(this::toPostponementItem).toList();
        return new ScheduleResponse(row.scheduleKey(), row.windowId(), row.channelId(),
                WaterService.toIso(row.originalStartNanos()), WaterService.toIso(row.originalEndNanos()),
                WaterService.toIso(row.startNanos()), WaterService.toIso(row.endNanos()),
                row.status(), started, WaterService.toIso(row.createdNanos()),
                WaterService.toIso(row.updatedNanos()), items);
    }

    private PostponementItem toPostponementItem(PostponementRow row) {
        return new PostponementItem(row.outageKey(), row.reason(), row.postponeMinutes(),
                WaterService.toIso(row.originalStartNanos()), WaterService.toIso(row.originalEndNanos()),
                WaterService.toIso(row.fromStartNanos()), WaterService.toIso(row.fromEndNanos()),
                WaterService.toIso(row.toStartNanos()), WaterService.toIso(row.toEndNanos()),
                WaterService.toIso(row.createdNanos()));
    }

    private OutageResponse toOutageResponse(OutageRow outage) {
        List<AffectedSchedule> affected = repository.listPostponementsByOutage(outage.id()).stream()
                .map(p -> new AffectedSchedule(scheduleKeyOf(p.scheduleId()),
                        WaterService.toIso(p.originalStartNanos()), WaterService.toIso(p.originalEndNanos()),
                        WaterService.toIso(p.fromStartNanos()), WaterService.toIso(p.fromEndNanos()),
                        WaterService.toIso(p.toStartNanos()), WaterService.toIso(p.toEndNanos()),
                        p.postponeMinutes()))
                .toList();
        return new OutageResponse(outage.outageKey(), outage.channelId(),
                WaterService.toIso(outage.startNanos()), WaterService.toIso(outage.endNanos()),
                outage.reason(), outage.postponeMinutes(), outage.status(),
                WaterService.toIso(outage.createdNanos()), affected);
    }

    private String scheduleKeyOf(long scheduleId) {
        ScheduleRow schedule = repository.findScheduleById(scheduleId);
        if (schedule == null) {
            throw new IllegalStateException("顺延记录引用的排班不存在: " + scheduleId);
        }
        return schedule.scheduleKey();
    }

    private SettlementResponse toSettlementResponse(SettlementRow row) {
        return new SettlementResponse(row.settlementKey(), row.scheduleKey(),
                WaterService.toIso(row.scheduledStartNanos()), WaterService.toIso(row.scheduledEndNanos()),
                WaterService.fmt(row.volume()), row.status(), WaterService.toIso(row.createdNanos()));
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
