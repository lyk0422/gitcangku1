package com.example.starter.baggage;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.DwellDtos.DwellCheckRequest;
import com.example.starter.baggage.DwellDtos.DwellCheckResponse;
import com.example.starter.baggage.DwellDtos.DwellDetailResponse;
import com.example.starter.baggage.DwellDtos.DwellDiagnosticsResponse;
import com.example.starter.baggage.DwellDtos.DwellItem;
import com.example.starter.baggage.DwellDtos.DwellRecordDto;
import com.example.starter.baggage.DwellDtos.DwellReleaseRequest;
import com.example.starter.baggage.DwellDtos.DwellReleaseResponse;
import com.example.starter.baggage.DwellDtos.LegConstraintConfigRequest;
import com.example.starter.baggage.DwellDtos.LegConstraintResponse;
import com.example.starter.baggage.DwellDtos.ReassignRequest;
import com.example.starter.baggage.DwellDtos.ReassignResponse;
import com.example.starter.baggage.DwellDtos.TransitArrivalItem;
import com.example.starter.baggage.DwellDtos.TransitArrivalItemResult;
import com.example.starter.baggage.DwellDtos.TransitArrivalRequest;
import com.example.starter.baggage.DwellDtos.TransitArrivalResponse;
import com.example.starter.baggage.DwellDtos.TransitPointConfigRequest;
import com.example.starter.baggage.DwellDtos.TransitPointConfigResponse;

/**
 * 行李滞留监控核心业务。
 *
 * <p>每个中转点配置最大滞留分钟数；行李批量到达中转点后登记开放滞留周期，
 * 按可注入时钟裁决：当前时刻落入 [arrivedAt, arrivedAt+max) 为 WITHIN_LIMIT，
 * 到达右端点（左闭右开）即判 DWELL_EXCEEDED，转入超时状态并阻断后续装载与改派。
 * 处理人登记复核结论可解除阻断，但必须指定属于行程的下一航段，并按裁决时刻重新校验
 * 航段截单（左闭右开）与容量（已装件数 + 1 &lt;= 容量）。</p>
 *
 * <p>到达、超时、解除与改派均追加不可变的 dwell_record（(bag_tag, seq) 唯一），
 * 超时证据快照冗余于每条记录，后续航段变更不改写；当前开放周期存于 bag_dwell_state，
 * 正常装载离站或解除后删除。所有写操作经 {@link IdempotencyService} 去重，
 * 指纹含操作类型、操作者与规范化（排序）对象集合，集合以结构化 JSON 参与摘要。</p>
 */
@Service
public class DwellService {

    static final String STATUS_WITHIN = "WITHIN_LIMIT";
    static final String STATUS_EXCEEDED = "DWELL_EXCEEDED";

    static final String REC_ARRIVAL = "ARRIVAL";
    static final String REC_EXCEEDED = "DWELL_EXCEEDED";
    static final String REC_RELEASED = "RELEASED";
    static final String REC_REASSIGNED = "REASSIGNED";

    private static final RowMapper<ConfigRow> CONFIG_MAPPER = (rs, n) -> new ConfigRow(
            rs.getString("station_code"), rs.getInt("max_dwell_minutes"));

    private static final RowMapper<DwellStateRow> STATE_MAPPER = (rs, n) -> new DwellStateRow(
            rs.getString("bag_tag"), rs.getString("station"), rs.getString("dwell_status"),
            getInstant(rs, "arrived_at"), rs.getInt("max_dwell_minutes"));

    private static final RowMapper<DwellRecordDto> RECORD_MAPPER = (rs, n) -> new DwellRecordDto(
            rs.getInt("seq"), rs.getString("record_type"), rs.getString("station"),
            getInstant(rs, "arrived_at").toString(), getInstant(rs, "event_time").toString(),
            rs.getObject("max_dwell_minutes", Long.class),
            rs.getObject("actual_dwell_minutes", Long.class),
            rs.getObject("difference_minutes", Long.class),
            instantString(rs, "deadline_at"), rs.getString("next_leg_id"),
            rs.getString("reviewer"), rs.getString("conclusion"), rs.getString("operator"),
            rs.getObject("leg_capacity", Integer.class),
            rs.getObject("leg_loaded_count", Integer.class),
            rs.getObject("leg_remaining_capacity", Integer.class),
            instantString(rs, "checkin_deadline"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public DwellService(JdbcTemplate jdbcTemplate, IdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
    }

    /** 替换时钟（测试使用），所有裁决时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 当前裁决时刻：统一截断到微秒，与 H2 TIMESTAMP WITH TIME ZONE 存储精度一致，
     * 保证历史证据写读后时间精度稳定（不丢纳秒导致的重放差异）。
     */
    private Instant now() {
        return clock.get().truncatedTo(ChronoUnit.MICROS);
    }

    /** 配置中转点最大滞留分钟数：同站重复配置以最新值覆盖（upsert）。 */
    public TransitPointConfigResponse configureTransitPoint(TransitPointConfigRequest request) {
        ConfigPayload payload = new ConfigPayload(request.stationCode(), request.maxDwellMinutes());
        return idempotencyService.execute(request.requestId(), "CONFIG_TRANSIT_POINT", 201,
                payload, TransitPointConfigResponse.class, () -> doConfigurePoint(request));
    }

    /** 配置航段容量与截单时刻：capacity/deadline 为 null 的项表示不限制，重复配置整体覆盖。 */
    public LegConstraintResponse configureLegConstraint(LegConstraintConfigRequest request) {
        Instant deadline = request.checkinDeadline() == null || request.checkinDeadline().isBlank()
                ? null : parseInstant(request.checkinDeadline(), "checkinDeadline");
        LegConstraintPayload payload = new LegConstraintPayload(
                request.legId(), request.capacity(), deadline == null ? null : deadline.toString());
        return idempotencyService.execute(request.requestId(), "CONFIG_LEG_CONSTRAINT", 201,
                payload, LegConstraintResponse.class,
                () -> doConfigureLegConstraint(request, deadline));
    }

    /** 批量到达核对：任一袋号状态非法整次回滚；中转点缺配置或到达时刻缺失返回 422。 */
    public TransitArrivalResponse arriveAtTransitPoint(TransitArrivalRequest request) {
        List<NormalizedArrival> normalized = normalizeArrivals(request.items());
        ArrivalPayload payload = new ArrivalPayload(
                request.stationCode(), request.operator(),
                normalized.stream().map(NormalizedArrival::bagTag).sorted().toList(),
                normalized.stream().sorted((a, b) -> a.bagTag().compareTo(b.bagTag()))
                        .map(item -> new ArrivalItemPayload(item.bagTag(), item.arrivedAt().toString()))
                        .toList());
        return idempotencyService.execute(request.requestId(), "TRANSIT_ARRIVAL", 200,
                payload, TransitArrivalResponse.class,
                () -> doArriveAtTransitPoint(request, normalized));
    }

    /** 超时裁决：bagTags 为 null 裁决该中转点全部在滞行李；超时者转 DWELL_EXCEEDED 并阻断。 */
    public DwellCheckResponse checkDwell(DwellCheckRequest request) {
        List<String> bagTags = request.bagTags() == null ? null : sortedCopy(request.bagTags());
        CheckPayload payload = new CheckPayload(request.stationCode(), request.operator(), bagTags);
        return idempotencyService.execute(request.requestId(), "DWELL_CHECK", 200,
                payload, DwellCheckResponse.class,
                () -> doCheckDwell(request.stationCode(), bagTags));
    }

    /** 解除阻断：下一航段必须属于行程且为当前待乘航段，重新校验截单时刻与容量。 */
    public DwellReleaseResponse release(DwellReleaseRequest request) {
        ReleasePayload payload = new ReleasePayload(
                request.bagTag(), request.nextLegId(), request.reviewer(), request.conclusion());
        return idempotencyService.execute(request.requestId(), "DWELL_RELEASE", 200,
                payload, DwellReleaseResponse.class, () -> doRelease(request));
    }

    /** 改派下一航段：仅追加不可变记录；超时阻断中的行李禁止改派（409），证据不改写。 */
    public ReassignResponse reassign(ReassignRequest request) {
        ReassignPayload payload = new ReassignPayload(
                request.bagTag(), request.nextLegId(), request.operator());
        return idempotencyService.execute(request.requestId(), "REASSIGN", 200,
                payload, ReassignResponse.class, () -> doReassign(request));
    }

    /** 行李滞留明细：current 为当前开放周期（无则 null），history 按 seq 升序不可变。 */
    public DwellDetailResponse getDetail(String bagTag) {
        if (findBag(bagTag) == null) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        Instant now = now();
        DwellStateRow state = findState(bagTag, false);
        DwellItem current = state == null ? null : toDwellItem(state, now);
        List<DwellRecordDto> history = jdbcTemplate.query(
                "SELECT seq, record_type, station, arrived_at, event_time, max_dwell_minutes,"
                        + " actual_dwell_minutes, difference_minutes, deadline_at, next_leg_id,"
                        + " reviewer, conclusion, operator, leg_capacity, leg_loaded_count,"
                        + " leg_remaining_capacity, checkin_deadline FROM dwell_record"
                        + " WHERE bag_tag = ? ORDER BY seq",
                RECORD_MAPPER, bagTag);
        return new DwellDetailResponse(bagTag, current, history);
    }

    /** 滞留诊断查询：只读计算，不改变状态；stationCode 为 null 查全部，按 (station, bagTag) 排序。 */
    public DwellDiagnosticsResponse getDiagnostics(String stationCode) {
        Instant now = now();
        List<DwellStateRow> rows;
        if (stationCode == null || stationCode.isBlank()) {
            rows = jdbcTemplate.query("SELECT bag_tag, station, dwell_status, arrived_at, max_dwell_minutes"
                    + " FROM bag_dwell_state", STATE_MAPPER);
        } else {
            rows = jdbcTemplate.query("SELECT bag_tag, station, dwell_status, arrived_at, max_dwell_minutes"
                    + " FROM bag_dwell_state WHERE station = ?", STATE_MAPPER, stationCode);
        }
        List<DwellItem> items = rows.stream()
                .sorted((a, b) -> {
                    int byStation = a.station().compareTo(b.station());
                    return byStation != 0 ? byStation : a.bagTag().compareTo(b.bagTag());
                })
                .map(row -> toDwellItem(row, now))
                .toList();
        return new DwellDiagnosticsResponse(now.toString(), items);
    }

    /**
     * 装载前在同一事务内校验滞留阻断：持有行李行锁后对在滞行加锁，
     * 超时先提交则本方法抛 409；返回 true 表示存在开放在滞周期（装载成功后应由调用方关闭）。
     */
    public boolean assertNotBlockedForLoad(String bagTag) {
        DwellStateRow state = findState(bagTag, true);
        if (state != null && STATUS_EXCEEDED.equals(state.dwellStatus())) {
            throw ApiException.conflict(
                    "行李 " + bagTag + " 已滞留超时（DWELL_EXCEEDED），须复核解除阻断后方可装载");
        }
        return state != null;
    }

    /** 行李成功装载离站后关闭其未超时的开放滞留周期（超时行已被装载校验拦截）。 */
    public void closeOpenStateOnDeparture(String bagTag) {
        jdbcTemplate.update("DELETE FROM bag_dwell_state WHERE bag_tag = ?", bagTag);
    }

    // ------------------------------------------------------------------
    // 业务实现
    // ------------------------------------------------------------------

    private TransitPointConfigResponse doConfigurePoint(TransitPointConfigRequest request) {
        upsertConfig(request.stationCode(), request.maxDwellMinutes());
        return new TransitPointConfigResponse(request.stationCode(), request.maxDwellMinutes());
    }

    private LegConstraintResponse doConfigureLegConstraint(LegConstraintConfigRequest request,
                                                           Instant deadline) {
        LegRow leg = findLeg(request.legId());
        if (leg == null) {
            throw ApiException.unprocessable("航段不存在: " + request.legId());
        }
        Integer updated = jdbcTemplate.update(
                "UPDATE leg_constraint SET capacity = ?, checkin_deadline = ? WHERE leg_id = ?",
                request.capacity(), toOffset(deadline), request.legId());
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO leg_constraint (leg_id, capacity, checkin_deadline) VALUES (?, ?, ?)",
                        request.legId(), request.capacity(), toOffset(deadline));
            } catch (DuplicateKeyException duplicate) {
                jdbcTemplate.update(
                        "UPDATE leg_constraint SET capacity = ?, checkin_deadline = ? WHERE leg_id = ?",
                        request.capacity(), toOffset(deadline), request.legId());
            }
        }
        return new LegConstraintResponse(request.legId(), request.capacity(),
                deadline == null ? null : deadline.toString());
    }

    private TransitArrivalResponse doArriveAtTransitPoint(TransitArrivalRequest request,
                                                          List<NormalizedArrival> normalized) {
        ConfigRow config = lockConfig(request.stationCode());
        if (config == null) {
            throw ApiException.unprocessable("中转点缺滞留配置: " + request.stationCode());
        }
        Set<String> uniqueTags = new HashSet<>();
        for (NormalizedArrival item : normalized) {
            uniqueTags.add(item.bagTag());
        }
        if (uniqueTags.size() != normalized.size()) {
            throw ApiException.unprocessable("批量到达的 bagTag 不得重复");
        }
        // 先完整校验最终状态：锁定全部行李并校验在站、待乘、无既有在滞周期
        List<ArrivalCandidate> candidates = new ArrayList<>();
        for (NormalizedArrival item : normalized.stream()
                .sorted((a, b) -> a.bagTag().compareTo(b.bagTag())).toList()) {
            BagRowLite bag = lockBag(item.bagTag());
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + item.bagTag());
            }
            if (!isArrivableAt(bag, request.stationCode())) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 状态为 " + bag.status()
                                + "，不具备在中转点 " + request.stationCode() + " 到达核对的合法状态");
            }
            if (findState(item.bagTag(), true) != null) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 已存在未关闭的滞留周期，不得重复到达登记");
            }
            candidates.add(new ArrivalCandidate(item, bag));
        }
        Instant now = now();
        List<TransitArrivalItemResult> results = new ArrayList<>();
        for (ArrivalCandidate candidate : candidates) {
            NormalizedArrival item = candidate.item();
            Instant deadline = item.arrivedAt().plusSeconds(60L * config.maxDwellMinutes());
            jdbcTemplate.update(
                    "INSERT INTO bag_dwell_state (bag_tag, station, dwell_status, arrived_at, max_dwell_minutes)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    item.bagTag(), request.stationCode(), STATUS_WITHIN,
                    toOffset(item.arrivedAt()), config.maxDwellMinutes());
            insertRecord(item.bagTag(), REC_ARRIVAL, request.stationCode(), item.arrivedAt(),
                    item.arrivedAt(), (long) config.maxDwellMinutes(), 0L,
                    (long) config.maxDwellMinutes(), deadline, null, null, null, request.operator(),
                    null, null, null, null);
            results.add(new TransitArrivalItemResult(item.bagTag(), request.stationCode(),
                    item.arrivedAt().toString(), config.maxDwellMinutes(), deadline.toString()));
        }
        List<TransitArrivalItemResult> sortedResults = results.stream()
                .sorted((a, b) -> a.bagTag().compareTo(b.bagTag()))
                .toList();
        return new TransitArrivalResponse(request.stationCode(), sortedResults.size(), sortedResults);
    }

    private DwellCheckResponse doCheckDwell(String stationCode, List<String> bagTags) {
        ConfigRow config = lockConfig(stationCode);
        if (config == null) {
            throw ApiException.unprocessable("中转点缺滞留配置: " + stationCode);
        }
        Instant now = now();
        List<String> targets;
        boolean explicit;
        if (bagTags == null || bagTags.isEmpty()) {
            // null 或空集均表示裁决该中转点全部在滞行李（只读快照，不改变状态）
            explicit = false;
            targets = jdbcTemplate.queryForList(
                    "SELECT bag_tag FROM bag_dwell_state WHERE station = ? ORDER BY bag_tag",
                    String.class, stationCode);
        } else {
            explicit = true;
            if (new HashSet<>(bagTags).size() != bagTags.size()) {
                throw ApiException.unprocessable("超时判定的 bagTags 不得重复");
            }
            targets = bagTags;
        }
        // 阶段一：按 bag -> state 顺序加锁（与装载一致，避免死锁）并完整校验最终状态。
        // 扫描模式下某行可能被并发装载离站删除：持袋锁后发现无在滞记录则跳过（属合法离站），
        // 显式指定模式下缺失在滞记录仍为 422。
        List<CheckTarget> targets2 = new ArrayList<>();
        for (String bagTag : targets) {
            BagRowLite bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            DwellStateRow state = findState(bagTag, true);
            if (state == null || !stationCode.equals(state.station())) {
                if (explicit) {
                    throw ApiException.unprocessable(
                            "行李 " + bagTag + " 在中转点 " + stationCode + " 无在滞记录，不能参与超时判定");
                }
                continue;
            }
            long actualMinutes = Duration.between(state.arrivedAt(), now).toMinutes();
            Instant deadline = state.arrivedAt().plusSeconds(60L * state.maxDwellMinutes());
            boolean exceeded = !now.isBefore(deadline);
            targets2.add(new CheckTarget(state, exceeded, actualMinutes, deadline));
        }
        // 阶段二：全部校验通过后，在同一事务中提交状态翻转与不可变证据
        int exceededCount = 0;
        List<DwellItem> items = new ArrayList<>();
        for (CheckTarget target : targets2) {
            DwellStateRow state = target.state();
            if (target.exceeded()) {
                exceededCount++;
                if (!STATUS_EXCEEDED.equals(state.dwellStatus())) {
                    jdbcTemplate.update(
                            "UPDATE bag_dwell_state SET dwell_status = ? WHERE bag_tag = ?",
                            STATUS_EXCEEDED, state.bagTag());
                    long excess = target.actualMinutes() - state.maxDwellMinutes();
                    insertRecord(state.bagTag(), REC_EXCEEDED, stationCode, state.arrivedAt(), now,
                            (long) state.maxDwellMinutes(), target.actualMinutes(), excess,
                            target.deadline(), null, null, null, null, null, null, null, null);
                }
                state = new DwellStateRow(state.bagTag(), stationCode, STATUS_EXCEEDED,
                        state.arrivedAt(), state.maxDwellMinutes());
            }
            items.add(toDwellItem(state, now));
        }
        List<DwellItem> sortedItems = items.stream()
                .sorted((a, b) -> a.bagTag().compareTo(b.bagTag()))
                .toList();
        return new DwellCheckResponse(stationCode, now.toString(), exceededCount, sortedItems);
    }

    private DwellReleaseResponse doRelease(DwellReleaseRequest request) {
        BagRowLite bag = lockBag(request.bagTag());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        DwellStateRow state = findState(request.bagTag(), true);
        if (state == null) {
            throw ApiException.unprocessable(
                    "行李 " + request.bagTag() + " 无开放滞留周期，不能解除阻断");
        }
        if (!STATUS_EXCEEDED.equals(state.dwellStatus())) {
            throw ApiException.unprocessable(
                    "行李 " + request.bagTag() + " 滞留未超上限（WITHIN_LIMIT），无需解除阻断");
        }
        // 下一航段必须属于行程且为当前待乘航段
        ItineraryRow next = findItinerary(request.bagTag(), bag.nextLegIndex());
        if (next == null || !next.legId().equals(request.nextLegId())) {
            throw ApiException.unprocessable(
                    "下一航段 " + request.nextLegId() + " 不属于行李 " + request.bagTag()
                            + " 的当前待乘行程");
        }
        LegRow leg = findLeg(request.nextLegId());
        if (leg == null) {
            throw ApiException.unprocessable("下一航段不存在: " + request.nextLegId());
        }
        if (!"OPEN".equals(leg.status())) {
            throw ApiException.unprocessable(
                    "下一航段状态为 " + leg.status() + "，解除阻断时不允许装载安排");
        }
        Instant now = now();
        ConstraintRow constraint = findConstraint(request.nextLegId());
        Instant deadline = state.arrivedAt().plusSeconds(60L * state.maxDwellMinutes());
        // 截单时刻按左闭右开：now >= checkinDeadline 即拒绝
        if (constraint != null && constraint.checkinDeadline() != null
                && !now.isBefore(constraint.checkinDeadline())) {
            throw ApiException.unprocessable(
                    "已超过下一航段 " + request.nextLegId() + " 截单时刻 "
                            + constraint.checkinDeadline() + "（当前 " + now + "，左闭右开）");
        }
        int loadedCount = countLoaded(request.nextLegId());
        Integer capacity = constraint == null ? null : constraint.capacity();
        Integer remaining = capacity == null ? null : capacity - loadedCount;
        if (capacity != null && loadedCount + 1 > capacity) {
            throw ApiException.unprocessable(
                    "下一航段容量不足：要求容量 " + capacity + " 件，实际已装 " + loadedCount
                            + " 件，剩余差额 " + (capacity - loadedCount) + " 件");
        }
        long actualMinutes = Duration.between(state.arrivedAt(), now).toMinutes();
        long remainingMinutes = state.maxDwellMinutes() - actualMinutes;
        insertRecord(request.bagTag(), REC_RELEASED, state.station(), state.arrivedAt(), now,
                (long) state.maxDwellMinutes(), actualMinutes, remainingMinutes, deadline,
                request.nextLegId(), request.reviewer(), request.conclusion(), null,
                capacity, loadedCount, remaining,
                constraint == null ? null : constraint.checkinDeadline());
        jdbcTemplate.update("DELETE FROM bag_dwell_state WHERE bag_tag = ?", request.bagTag());
        return new DwellReleaseResponse(request.bagTag(), "RELEASED", state.station(),
                request.nextLegId(), request.reviewer(), now.toString(),
                state.arrivedAt().toString(), actualMinutes, state.maxDwellMinutes(),
                remainingMinutes, capacity, loadedCount, remaining,
                constraint == null || constraint.checkinDeadline() == null
                        ? null : constraint.checkinDeadline().toString());
    }

    private ReassignResponse doReassign(ReassignRequest request) {
        BagRowLite bag = lockBag(request.bagTag());
        if (bag == null) {
            throw ApiException.notFound("行李不存在: " + request.bagTag());
        }
        DwellStateRow state = findState(request.bagTag(), true);
        if (state != null && STATUS_EXCEEDED.equals(state.dwellStatus())) {
            throw ApiException.conflict(
                    "行李 " + request.bagTag() + " 处于超时阻断状态，改派被阻断，须先复核解除");
        }
        LegRow leg = findLeg(request.nextLegId());
        if (leg == null) {
            throw ApiException.unprocessable("改派航段不存在: " + request.nextLegId());
        }
        if (!leg.origin().equals(bag.currentLocation())) {
            throw ApiException.unprocessable(
                    "改派航段始发站 " + leg.origin() + " 与行李当前所在站 "
                            + bag.currentLocation() + " 不一致");
        }
        Instant now = now();
        // 改派记录锚定最近一次滞留周期证据；无任何滞留历史时以当前站与当前时刻作为锚点
        EvidenceAnchor anchor = latestEvidenceAnchor(request.bagTag());
        String anchorStation = state != null ? state.station()
                : (anchor == null ? bag.currentLocation() : anchor.station());
        Instant anchorArrival = state != null ? state.arrivedAt()
                : (anchor == null ? now : anchor.arrivedAt());
        insertRecord(request.bagTag(), REC_REASSIGNED, anchorStation, anchorArrival, now,
                null, null, null, null, request.nextLegId(), null, null, request.operator(),
                null, null, null, null);
        return new ReassignResponse(request.bagTag(), request.nextLegId(),
                request.operator(), now.toString());
    }

    // ------------------------------------------------------------------
    // 行访问与组装
    // ------------------------------------------------------------------

    /** 行李是否具备在指定中转点到达核对的合法状态：在途/已补到、未装载、待乘航段自本站起飞。 */
    private boolean isArrivableAt(BagRowLite bag, String stationCode) {
        if (!"IN_TRANSIT".equals(bag.status()) && !"RECOVERED".equals(bag.status())) {
            return false;
        }
        if (bag.loadedLegId() != null || !stationCode.equals(bag.currentLocation())) {
            return false;
        }
        ItineraryRow next = findItinerary(bag.bagTag(), bag.nextLegIndex());
        return next != null && stationCode.equals(next.origin());
    }

    private void upsertConfig(String stationCode, int maxDwellMinutes) {
        Integer updated = jdbcTemplate.update(
                "UPDATE transit_point_config SET max_dwell_minutes = ? WHERE station_code = ?",
                maxDwellMinutes, stationCode);
        if (updated == 0) {
            try {
                jdbcTemplate.update(
                        "INSERT INTO transit_point_config (station_code, max_dwell_minutes) VALUES (?, ?)",
                        stationCode, maxDwellMinutes);
            } catch (DuplicateKeyException duplicate) {
                jdbcTemplate.update(
                        "UPDATE transit_point_config SET max_dwell_minutes = ? WHERE station_code = ?",
                        maxDwellMinutes, stationCode);
            }
        }
    }

    private ConfigRow lockConfig(String stationCode) {
        List<ConfigRow> rows = jdbcTemplate.query(
                "SELECT station_code, max_dwell_minutes FROM transit_point_config"
                        + " WHERE station_code = ? FOR UPDATE",
                CONFIG_MAPPER, stationCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRowLite lockBag(String bagTag) {
        List<BagRowLite> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id"
                        + " FROM bag WHERE bag_tag = ? FOR UPDATE",
                (rs, n) -> new BagRowLite(rs.getString("bag_tag"), rs.getString("current_location"),
                        rs.getInt("next_leg_index"), rs.getString("status"),
                        rs.getString("loaded_leg_id")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private DwellStateRow findState(String bagTag, boolean forUpdate) {
        List<DwellStateRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, station, dwell_status, arrived_at, max_dwell_minutes"
                        + " FROM bag_dwell_state WHERE bag_tag = ?" + (forUpdate ? " FOR UPDATE" : ""),
                STATE_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRowLite findBag(String bagTag) {
        List<BagRowLite> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id"
                        + " FROM bag WHERE bag_tag = ?",
                (rs, n) -> new BagRowLite(rs.getString("bag_tag"), rs.getString("current_location"),
                        rs.getInt("next_leg_index"), rs.getString("status"),
                        rs.getString("loaded_leg_id")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow findLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status FROM leg WHERE leg_id = ?",
                (rs, n) -> new LegRow(rs.getString("leg_id"), rs.getString("origin"),
                        rs.getString("destination"), rs.getString("status")),
                legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ConstraintRow findConstraint(String legId) {
        List<ConstraintRow> rows = jdbcTemplate.query(
                "SELECT leg_id, capacity, checkin_deadline FROM leg_constraint WHERE leg_id = ?",
                (rs, n) -> new ConstraintRow(rs.getString("leg_id"),
                        rs.getObject("capacity", Integer.class), getInstant(rs, "checkin_deadline")),
                legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ItineraryRow findItinerary(String bagTag, int seq) {
        List<ItineraryRow> rows = jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary"
                        + " WHERE bag_tag = ? AND seq = ?",
                (rs, n) -> new ItineraryRow(rs.getInt("seq"), rs.getString("leg_id"),
                        rs.getString("origin"), rs.getString("destination")),
                bagTag, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近一条不可变滞留记录的到达时刻与站点锚点；无记录返回 null。 */
    private EvidenceAnchor latestEvidenceAnchor(String bagTag) {
        List<EvidenceAnchor> rows = jdbcTemplate.query(
                "SELECT station, arrived_at FROM dwell_record WHERE bag_tag = ?"
                        + " ORDER BY seq DESC LIMIT 1",
                (rs, n) -> new EvidenceAnchor(rs.getString("station"), getInstant(rs, "arrived_at")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int countLoaded(String legId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM load_record WHERE leg_id = ?", Integer.class, legId);
        return count == null ? 0 : count;
    }

    /** 在持有行李行锁后追加不可变滞留记录，seq 按该行李已有记录数递增。 */
    private void insertRecord(String bagTag, String recordType, String station,
                              Instant arrivedAt, Instant eventTime, Long maxDwellMinutes,
                              Long actualDwellMinutes, Long differenceMinutes, Instant deadline,
                              String nextLegId, String reviewer, String conclusion, String operator,
                              Integer legCapacity, Integer legLoadedCount,
                              Integer legRemainingCapacity, Instant checkinDeadline) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM dwell_record WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO dwell_record (bag_tag, seq, record_type, station, arrived_at, event_time,"
                        + " max_dwell_minutes, actual_dwell_minutes, difference_minutes, deadline_at,"
                        + " next_leg_id, reviewer, conclusion, operator, leg_capacity, leg_loaded_count,"
                        + " leg_remaining_capacity, checkin_deadline)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, recordType, station, toOffset(arrivedAt), toOffset(eventTime),
                maxDwellMinutes, actualDwellMinutes, differenceMinutes, toOffset(deadline),
                nextLegId, reviewer, conclusion, operator, legCapacity, legLoadedCount,
                legRemainingCapacity, toOffset(checkinDeadline));
    }

    private DwellItem toDwellItem(DwellStateRow state, Instant now) {
        long actualMinutes = Duration.between(state.arrivedAt(), now).toMinutes();
        Instant deadline = state.arrivedAt().plusSeconds(60L * state.maxDwellMinutes());
        boolean exceeded = STATUS_EXCEEDED.equals(state.dwellStatus());
        long difference = exceeded ? actualMinutes - state.maxDwellMinutes()
                : state.maxDwellMinutes() - actualMinutes;
        return new DwellItem(state.bagTag(), state.station(), state.dwellStatus(),
                exceeded, state.arrivedAt().toString(), deadline.toString(),
                (long) state.maxDwellMinutes(), actualMinutes, difference, now.toString());
    }

    private List<NormalizedArrival> normalizeArrivals(List<TransitArrivalItem> items) {
        List<NormalizedArrival> normalized = new ArrayList<>();
        for (TransitArrivalItem item : items) {
            if (item.arrivedAt() == null || item.arrivedAt().isBlank()) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 的到达时刻缺失");
            }
            normalized.add(new NormalizedArrival(item.bagTag(),
                    parseInstant(item.arrivedAt(), "arrivedAt")));
        }
        return normalized;
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw ApiException.unprocessable(field + " 不是合法的 UTC 时刻: " + value);
        }
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String instantString(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Instant value = getInstant(rs, column);
        return value == null ? null : value.toString();
    }

    private static List<String> sortedCopy(List<String> values) {
        return values.stream().sorted().toList();
    }

    // ------------------------------------------------------------------
    // 内部行与幂等载荷
    // ------------------------------------------------------------------

    private record ConfigRow(String stationCode, int maxDwellMinutes) {
    }

    private record LegRow(String legId, String origin, String destination, String status) {
    }

    private record ConstraintRow(String legId, Integer capacity, Instant checkinDeadline) {
    }

    private record ItineraryRow(int seq, String legId, String origin, String destination) {
    }

    private record BagRowLite(String bagTag, String currentLocation, int nextLegIndex,
                              String status, String loadedLegId) {
    }

    private record DwellStateRow(String bagTag, String station, String dwellStatus,
                                 Instant arrivedAt, int maxDwellMinutes) {
    }

    private record NormalizedArrival(String bagTag, Instant arrivedAt) {
    }

    private record ArrivalCandidate(NormalizedArrival item, BagRowLite bag) {
    }

    /** 超时裁决阶段一锁定的目标及其在当前时钟下的完整裁决结果。 */
    private record CheckTarget(DwellStateRow state, boolean exceeded,
                               long actualMinutes, Instant deadline) {
    }

    private record EvidenceAnchor(String station, Instant arrivedAt) {
    }

    private record ConfigPayload(String stationCode, int maxDwellMinutes) {
    }

    private record LegConstraintPayload(String legId, Integer capacity, String checkinDeadline) {
    }

    /** 到达幂等载荷：items 按 bagTag 排序的结构化集合，包含操作者与全部影响结果字段。 */
    private record ArrivalPayload(String stationCode, String operator,
                                  List<String> bagTags, List<ArrivalItemPayload> items) {
    }

    private record ArrivalItemPayload(String bagTag, String arrivedAt) {
    }

    /** 超时裁决载荷：bagTags 为 null 表示裁决该中转点全部在滞行李。 */
    private record CheckPayload(String stationCode, String operator, List<String> bagTags) {
    }

    private record ReleasePayload(String bagTag, String nextLegId, String reviewer,
                                  String conclusion) {
    }

    private record ReassignPayload(String bagTag, String nextLegId, String operator) {
    }
}
