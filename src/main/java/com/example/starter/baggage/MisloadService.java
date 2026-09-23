package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.example.starter.baggage.MisloadDtos.BagPathLineageResponse;
import com.example.starter.baggage.MisloadDtos.ConfirmRerouteRequest;
import com.example.starter.baggage.MisloadDtos.ConfirmRerouteResponse;
import com.example.starter.baggage.MisloadDtos.MisloadIncidentResponse;
import com.example.starter.baggage.MisloadDtos.MisloadItemResponse;
import com.example.starter.baggage.MisloadDtos.PathSnapshotItem;
import com.example.starter.baggage.MisloadDtos.PreviewRerouteRequest;
import com.example.starter.baggage.MisloadDtos.PreviewRerouteResponse;
import com.example.starter.baggage.MisloadDtos.RegisterMisloadRequest;
import com.example.starter.baggage.MisloadDtos.RegisterMisloadResponse;
import com.example.starter.baggage.MisloadDtos.RerouteBagRequest;
import com.example.starter.baggage.MisloadDtos.ReroutePreviewItem;
import com.example.starter.baggage.MisloadDtos.RerouteResultItem;

/**
 * 错装行李批次追回与剩余路径原子改派核心业务。
 *
 * <p>登记：到达站用 incidentKey 登记 2~50 件在同一实际航段到达、但该航段不属于各自行程的行李，
 * 逐件冻结当前版本与扫描站点；任一已交付、已在途（已装载未到达）、已有未结错装或站点不符，
 * 整单 409/422 且无行李改态。</p>
 *
 * <p>预览：调度员逐件提交 1~5 段从当前站到原最终目的地的恢复路径，段间站点连续、
 * 出发时间严格递增、首末站精确匹配；预览冻结行李版本、原剩余路径与恢复路径。</p>
 *
 * <p>确认：重新校验全部行李状态与路径，任何恢复段已封舱即拒绝；成功后在单事务内原子关闭
 * 错装事件、替换各自剩余路径、推进路径代次并保存原/新路径不可变快照。此后原剩余路径
 * 不得再装载，已走完历史不改。</p>
 */
@Service
public class MisloadService {

    private static final String INCIDENT_OPEN = "OPEN";
    private static final String INCIDENT_CLOSED = "CLOSED";
    private static final String BAG_MISLOADED = "MISLOADED";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String KIND_ORIGINAL = "ORIGINAL";
    private static final String KIND_NEW = "NEW";

    private static final RowMapper<IncidentRow> INCIDENT_MAPPER = (rs, rowNum) -> new IncidentRow(
            rs.getString("incident_key"), rs.getString("actual_leg_id"),
            rs.getString("arrival_station"), rs.getString("status"),
            getInstant(rs, "created_at"), getInstant(rs, "closed_at"));

    private static final RowMapper<MisloadItemRow> ITEM_MAPPER = (rs, rowNum) -> new MisloadItemRow(
            rs.getString("incident_key"), rs.getString("bag_tag"),
            rs.getInt("bag_version"), rs.getString("scan_station"),
            getInstant(rs, "created_at"));

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, rowNum) -> new PlanRow(
            rs.getString("incident_key"), rs.getString("bag_tag"),
            rs.getInt("bag_version"), rs.getString("current_station"),
            rs.getString("final_destination"),
            rs.getString("original_remaining"), rs.getString("recovery_path"),
            getInstant(rs, "created_at"));

    private static final RowMapper<PathSnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new PathSnapshotRow(
            rs.getString("bag_tag"), rs.getInt("generation"), rs.getString("path_kind"),
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"),
            rs.getString("destination"), getInstant(rs, "created_at"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public MisloadService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    /** 替换时钟（测试使用），所有登记时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /**
     * 登记错装批次。整单原子：逐件校验全部通过后才统一转 MISLOADED 并冻结登记版本/扫描站；
     * 任一行李版本不符（409）或状态/站点/行程不符（422）则无行李改态。
     */
    public RegisterMisloadResponse register(RegisterMisloadRequest request) {
        List<RegisterBagPayload> sortedBags = request.bags().stream()
                .map(bag -> new RegisterBagPayload(bag.bagTag(), bag.expectedVersion(), bag.scanStation()))
                .sorted()
                .toList();
        RegisterPayload payload = new RegisterPayload(
                request.incidentKey(), request.actualLegId(), sortedBags);
        try {
            return idempotencyService.execute(request.requestId(), "REGISTER_MISLOAD", 201,
                    payload, RegisterMisloadResponse.class, () -> doRegister(request));
        } catch (DuplicateKeyException duplicate) {
            // incidentKey 唯一约束兜底：并发不同 requestId 登记同键，按 409 处理而非 500
            throw ApiException.conflict("错装事件已存在: " + request.incidentKey());
        }
    }

    /**
     * 预览恢复路径：逐件校验路径连续/严格递增/首末站精确匹配，
     * 冻结每件行李版本、原剩余路径与恢复路径。同一事件再次预览整组替换旧冻结。
     */
    public PreviewRerouteResponse preview(String incidentKey, PreviewRerouteRequest request) {
        List<PathPayload> sortedBags = request.bags().stream()
                .map(bag -> new PathPayload(bag.bagTag(), bag.path()))
                .sorted()
                .toList();
        PreviewPayload payload = new PreviewPayload(incidentKey, sortedBags);
        return idempotencyService.execute(request.requestId(), "PREVIEW_REROUTE", 200,
                payload, PreviewRerouteResponse.class, () -> doPreview(incidentKey, request));
    }

    /**
     * 确认原子改派：重新校验全部行李状态、冻结版本与恢复路径，任一恢复段已封舱即整单拒绝；
     * 成功后原子关闭事件、替换各自剩余路径、推进路径代次并保存原/新不可变快照。
     */
    public ConfirmRerouteResponse confirm(String incidentKey, ConfirmRerouteRequest request) {
        return idempotencyService.execute(request.requestId(), "CONFIRM_REROUTE", 200,
                new ConfirmPayload(incidentKey), ConfirmRerouteResponse.class,
                () -> doConfirm(incidentKey));
    }

    /** 错装批次只读查询：批次头、逐件登记与最新预览冻结。 */
    public MisloadIncidentResponse getIncident(String incidentKey) {
        IncidentRow incident = findIncident(incidentKey);
        if (incident == null) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        List<MisloadItemRow> items = jdbcTemplate.query(
                "SELECT incident_key, bag_tag, bag_version, scan_station, created_at"
                        + " FROM misload_item WHERE incident_key = ? ORDER BY bag_tag",
                ITEM_MAPPER, incidentKey);
        List<PlanRow> plans = jdbcTemplate.query(
                "SELECT incident_key, bag_tag, bag_version, current_station, final_destination,"
                        + " original_remaining, recovery_path, created_at"
                        + " FROM misload_reroute_plan WHERE incident_key = ? ORDER BY bag_tag",
                PLAN_MAPPER, incidentKey);
        return new MisloadIncidentResponse(
                incident.incidentKey(), incident.actualLegId(), incident.arrivalStation(),
                incident.status(), instant(incident.createdAt()), instant(incident.closedAt()),
                items.stream().map(item -> new MisloadItemResponse(
                        item.bagTag(), item.bagVersion(), item.scanStation(),
                        instant(item.createdAt()))).toList(),
                plans.stream().map(this::toPreviewItem).toList());
    }

    /** 逐件路径血缘只读查询：按代次与顺序返回全部原/新不可变快照。 */
    public BagPathLineageResponse getPathLineage(String bagTag) {
        Integer exists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        Integer generation = jdbcTemplate.queryForObject(
                "SELECT path_generation FROM bag WHERE bag_tag = ?", Integer.class, bagTag);
        List<PathSnapshotRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, generation, path_kind, seq, leg_id, origin, destination, created_at"
                        + " FROM path_snapshot WHERE bag_tag = ? ORDER BY generation, id",
                SNAPSHOT_MAPPER, bagTag);
        List<PathSnapshotItem> items = rows.stream()
                .map(row -> new PathSnapshotItem(row.pathKind(), row.generation(), row.seq(),
                        row.legId(), row.origin(), row.destination(), instant(row.createdAt())))
                .toList();
        return new BagPathLineageResponse(bagTag, generation == null ? 1 : generation, items);
    }

    private RegisterMisloadResponse doRegister(RegisterMisloadRequest request) {
        if (findIncident(request.incidentKey()) != null) {
            throw ApiException.conflict("错装事件已存在: " + request.incidentKey());
        }
        LegRow actualLeg = findLeg(request.actualLegId());
        if (actualLeg == null) {
            throw ApiException.unprocessable("实际航段不存在: " + request.actualLegId());
        }
        List<RegisterBagRow> submitted = request.bags().stream()
                .map(bag -> new RegisterBagRow(bag.bagTag(), bag.expectedVersion(), bag.scanStation()))
                .toList();
        Map<String, RegisterBagRow> submittedByTag = new LinkedHashMap<>();
        for (RegisterBagRow bag : submitted) {
            if (submittedByTag.put(bag.bagTag(), bag) != null) {
                throw ApiException.unprocessable("错装批次内 bagTag 不得重复: " + bag.bagTag());
            }
        }
        OffsetDateTime now = nowOffset();
        // 按 bagTag 排序加锁，避免与装载/到达等并发流程交叉等锁；先全部校验通过后才统一改态
        List<String> sortedTags = new ArrayList<>(submittedByTag.keySet());
        sortedTags.sort(String::compareTo);
        for (String bagTag : sortedTags) {
            RegisterBagRow submittedBag = submittedByTag.get(bagTag);
            BagRow bag = lockBag(bagTag);
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + bagTag);
            }
            if (bag.version() != submittedBag.expectedVersion()) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 版本冲突: 期望 " + submittedBag.expectedVersion()
                                + "，当前 " + bag.version());
            }
            if (BAG_DELIVERED.equals(bag.status())) {
                throw ApiException.unprocessable("行李 " + bag.bagTag() + " 已交付，不能登记错装");
            }
            if (bag.loadedLegId() != null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 已在途（已装载到 " + bag.loadedLegId() + "），不能登记错装");
            }
            if (BAG_MISLOADED.equals(bag.status()) || hasOpenIncident(bag.bagTag())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 已存在未结错装事件");
            }
            if (!actualLeg.destination().equals(submittedBag.scanStation())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 扫描站 " + submittedBag.scanStation()
                                + " 与实际航段到达站 " + actualLeg.destination() + " 不符");
            }
            if (!bag.currentLocation().equals(actualLeg.destination())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 当前站 " + bag.currentLocation()
                                + " 与扫描站 " + submittedBag.scanStation() + " 不符");
            }
            List<ItineraryRow> itinerary = findItinerary(bag.bagTag());
            if (itinerary.stream().anyMatch(item -> item.legId().equals(request.actualLegId()))) {
                throw ApiException.unprocessable(
                        "实际航段 " + request.actualLegId() + " 属于行李 " + bag.bagTag() + " 的行程，不构成错装");
            }
        }
        jdbcTemplate.update(
                "INSERT INTO misload_incident (incident_key, actual_leg_id, arrival_station,"
                        + " status, created_at) VALUES (?, ?, ?, ?, ?)",
                request.incidentKey(), request.actualLegId(), actualLeg.destination(),
                INCIDENT_OPEN, now);
        for (String bagTag : sortedTags) {
            RegisterBagRow submittedBag = submittedByTag.get(bagTag);
            jdbcTemplate.update(
                    "INSERT INTO misload_item (incident_key, bag_tag, bag_version, scan_station, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    request.incidentKey(), bagTag, submittedBag.expectedVersion(),
                    submittedBag.scanStation(), now);
            jdbcTemplate.update(
                    "UPDATE bag SET status = ?, version = version + 1,"
                            + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL"
                            + " WHERE bag_tag = ?",
                    BAG_MISLOADED, bagTag);
            insertEvent(bagTag, "MISLOADED", request.actualLegId(), actualLeg.destination(), now);
        }
        return new RegisterMisloadResponse(request.incidentKey(), request.actualLegId(),
                actualLeg.destination(), INCIDENT_OPEN, sortedTags);
    }

    private PreviewRerouteResponse doPreview(String incidentKey, PreviewRerouteRequest request) {
        IncidentRow incident = lockIncident(incidentKey);
        if (incident == null) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        if (INCIDENT_CLOSED.equals(incident.status())) {
            throw ApiException.conflict("错装事件 " + incidentKey + " 已关闭，不能再次预览");
        }
        List<RerouteBagRequest> submitted = request.bags();
        Map<String, List<String>> pathsByBag = new LinkedHashMap<>();
        for (RerouteBagRequest bag : submitted) {
            if (pathsByBag.put(bag.bagTag(), bag.path()) != null) {
                throw ApiException.unprocessable("预览内 bagTag 不得重复: " + bag.bagTag());
            }
        }
        List<MisloadItemRow> items = findItems(incidentKey);
        Set<String> registeredTags = new HashSet<>();
        for (MisloadItemRow item : items) {
            registeredTags.add(item.bagTag());
        }
        if (!registeredTags.equals(pathsByBag.keySet())) {
            throw ApiException.unprocessable(
                    "预览行李集合必须与错装登记逐件集合完全一致");
        }
        OffsetDateTime now = nowOffset();
        List<ReroutePreviewItem> previewItems = new ArrayList<>();
        // 逐件锁行李（misload_item 已按 bagTag 排序），冻结当前版本、原剩余路径与恢复路径
        for (MisloadItemRow item : items) {
            BagRow bag = lockBag(item.bagTag());
            List<ItineraryRow> itinerary = findItinerary(item.bagTag());
            if (!BAG_MISLOADED.equals(bag.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，不是未结错装状态");
            }
            List<String> remainingLegs = itinerary.subList(
                    Math.min(bag.nextLegIndex(), itinerary.size()), itinerary.size()).stream()
                    .map(ItineraryRow::legId).toList();
            List<String> recoveryPath = pathsByBag.get(item.bagTag());
            String finalDestination = itinerary.get(itinerary.size() - 1).destination();
            validateRecoveryPath(bag, recoveryPath, finalDestination);
            jdbcTemplate.update(
                    "MERGE INTO misload_reroute_plan (incident_key, bag_tag, bag_version,"
                            + " current_station, final_destination, original_remaining, recovery_path,"
                            + " created_at) KEY (incident_key, bag_tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    incidentKey, item.bagTag(), bag.version(), bag.currentLocation(),
                    finalDestination, writeJson(remainingLegs), writeJson(recoveryPath), now);
            previewItems.add(new ReroutePreviewItem(item.bagTag(), bag.version(),
                    bag.currentLocation(), finalDestination, remainingLegs, List.copyOf(recoveryPath)));
        }
        return new PreviewRerouteResponse(incidentKey, INCIDENT_OPEN, previewItems);
    }

    private ConfirmRerouteResponse doConfirm(String incidentKey) {
        IncidentRow incident = lockIncident(incidentKey);
        if (incident == null) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        if (INCIDENT_CLOSED.equals(incident.status())) {
            throw ApiException.conflict("错装事件 " + incidentKey + " 已关闭，不能重复确认");
        }
        List<PlanRow> plans = jdbcTemplate.query(
                "SELECT incident_key, bag_tag, bag_version, current_station, final_destination,"
                        + " original_remaining, recovery_path, created_at"
                        + " FROM misload_reroute_plan WHERE incident_key = ? ORDER BY bag_tag FOR UPDATE",
                PLAN_MAPPER, incidentKey);
        List<MisloadItemRow> items = findItems(incidentKey);
        if (plans.size() != items.size()) {
            throw ApiException.unprocessable(
                    "尚有行李未提交恢复路径预览，整单不能确认改派");
        }
        // 先解析并锁定全部恢复航段（去重、按 legId 排序），与装载/封舱的“先航段后行李”加锁顺序一致，
        // 避免与并发装载交叉等锁；持锁后状态校验在 READ COMMITTED 下才可靠。
        List<List<String>> recoveryPaths = plans.stream().map(plan -> readJsonList(plan.recoveryPath())).toList();
        List<String> allRecoveryLegIds = recoveryPaths.stream().flatMap(List::stream).distinct().sorted().toList();
        Map<String, LegRow> lockedLegs = new LinkedHashMap<>();
        for (String legId : allRecoveryLegIds) {
            LegRow leg = lockLeg(legId);
            if (leg == null) {
                throw ApiException.unprocessable("恢复路径引用的航段不存在: " + legId);
            }
            if (!"OPEN".equals(leg.status())) {
                throw ApiException.unprocessable(
                        "恢复段 " + legId + " 已封舱/到达，整单改派拒绝");
            }
            lockedLegs.put(legId, leg);
        }
        OffsetDateTime now = nowOffset();
        List<RerouteResultItem> results = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            PlanRow plan = plans.get(i);
            List<String> recoveryPath = recoveryPaths.get(i);
            BagRow bag = lockBag(plan.bagTag());
            // 重新校验全部行李状态与冻结版本
            if (!BAG_MISLOADED.equals(bag.status())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 状态为 " + bag.status() + "，改派确认拒绝");
            }
            if (bag.version() != plan.bagVersion()) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 预览后版本已变化，整单改派拒绝");
            }
            if (!bag.currentLocation().equals(plan.currentStation())) {
                throw ApiException.conflict(
                        "行李 " + bag.bagTag() + " 当前站已变化，整单改派拒绝");
            }
            // 重新校验路径：连续、严格递增、首末站精确匹配（航段已全部持锁，封舱检查在上方统一完成）
            List<LegRow> recoveryLegs = validateRecoveryPath(bag, recoveryPath,
                    plan.finalDestination(), lockedLegs);
            List<String> originalRemaining = readJsonList(plan.originalRemaining());
            int oldGeneration = bag.pathGeneration();
            int newGeneration = oldGeneration + 1;
            // 保存原/新路径不可变快照（只追加）
            insertSnapshots(incidentKey, plan.bagTag(), oldGeneration, KIND_ORIGINAL,
                    originalRemaining, now);
            insertSnapshots(incidentKey, plan.bagTag(), newGeneration, KIND_NEW,
                    recoveryPath, now);
            // 替换剩余路径：已走完历史（seq < nextLegIndex）不改，剩余行程整段替换为恢复路径
            replaceRemainingItinerary(plan.bagTag(), bag.nextLegIndex(), recoveryLegs);
            int newVersion = bag.version() + 1;
            jdbcTemplate.update(
                    "UPDATE bag SET path_generation = ?, version = ?, status = 'IN_TRANSIT',"
                            + " short_leg_id = NULL, short_destination = NULL, short_registered_at = NULL"
                            + " WHERE bag_tag = ?",
                    newGeneration, newVersion, plan.bagTag());
            insertEvent(plan.bagTag(), "REROUTED", null, bag.currentLocation(), now);
            results.add(new RerouteResultItem(plan.bagTag(), newVersion, newGeneration,
                    bag.currentLocation(), "IN_TRANSIT", recoveryPath));
        }
        jdbcTemplate.update(
                "UPDATE misload_incident SET status = ?, closed_at = ? WHERE incident_key = ?",
                INCIDENT_CLOSED, now, incidentKey);
        return new ConfirmRerouteResponse(incidentKey, INCIDENT_CLOSED, results);
    }

    /** 逐段校验恢复路径：1~5 段、不重复、站站连续、出发时间严格递增、首段起点与末段终点精确匹配。 */
    private void validateRecoveryPath(BagRow bag, List<String> recoveryPath, String finalDestination) {
        List<LegRow> legs = new ArrayList<>();
        for (String legId : recoveryPath) {
            LegRow leg = findLeg(legId);
            if (leg == null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 恢复路径引用的航段不存在: " + legId);
            }
            legs.add(leg);
        }
        validateRecoveryLegs(bag, recoveryPath, legs, finalDestination);
    }

    /** 确认时复用已加锁的恢复航段完成同样的路径校验。 */
    private List<LegRow> validateRecoveryPath(BagRow bag, List<String> recoveryPath,
                                              String finalDestination, Map<String, LegRow> lockedLegs) {
        List<LegRow> legs = new ArrayList<>();
        for (String legId : recoveryPath) {
            LegRow leg = lockedLegs.get(legId);
            if (leg == null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 恢复路径引用的航段不存在: " + legId);
            }
            legs.add(leg);
        }
        validateRecoveryLegs(bag, recoveryPath, legs, finalDestination);
        return legs;
    }

    private void validateRecoveryLegs(BagRow bag, List<String> recoveryPath,
                                      List<LegRow> legs, String finalDestination) {
        if (recoveryPath.isEmpty() || recoveryPath.size() > 5) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 恢复路径段数必须为 1~5");
        }
        if (new HashSet<>(recoveryPath).size() != recoveryPath.size()) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 恢复路径航段不得重复");
        }
        if (!legs.get(0).origin().equals(bag.currentLocation())) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 恢复路径首段起点 " + legs.get(0).origin()
                            + " 与当前站 " + bag.currentLocation() + " 不匹配");
        }
        if (!legs.get(legs.size() - 1).destination().equals(finalDestination)) {
            throw ApiException.unprocessable(
                    "行李 " + bag.bagTag() + " 恢复路径末段终点 "
                            + legs.get(legs.size() - 1).destination()
                            + " 与原最终目的地 " + finalDestination + " 不匹配");
        }
        for (int i = 0; i + 1 < legs.size(); i++) {
            LegRow current = legs.get(i);
            LegRow next = legs.get(i + 1);
            if (!current.destination().equals(next.origin())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 恢复路径段间站点不连续: "
                                + current.legId() + " -> " + next.legId());
            }
            if (current.departureTime() == null || next.departureTime() == null) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 恢复段 " + current.legId() + "/" + next.legId()
                                + " 未登记出发时间，无法校验严格递增");
            }
            if (!current.departureTime().isBefore(next.departureTime())) {
                throw ApiException.unprocessable(
                        "行李 " + bag.bagTag() + " 恢复路径出发时间必须严格递增: "
                                + current.legId() + " -> " + next.legId());
            }
        }
    }

    /** 保存一件行李某一路由代次的不可变快照。 */
    private void insertSnapshots(String incidentKey, String bagTag, int generation,
                                 String pathKind, List<String> legIds, OffsetDateTime now) {
        for (int seq = 0; seq < legIds.size(); seq++) {
            LegRow leg = findLeg(legIds.get(seq));
            jdbcTemplate.update(
                    "INSERT INTO path_snapshot (incident_key, bag_tag, generation, path_kind, seq,"
                            + " leg_id, origin, destination, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    incidentKey, bagTag, generation, pathKind, seq,
                    leg.legId(), leg.origin(), leg.destination(), now);
        }
    }

    /** 删除旧剩余行程并写入恢复航段，已走完历史（seq 小于待乘下标）保持不变。 */
    private void replaceRemainingItinerary(String bagTag, int nextLegIndex, List<LegRow> recoveryLegs) {
        jdbcTemplate.update(
                "DELETE FROM bag_itinerary WHERE bag_tag = ? AND seq >= ?", bagTag, nextLegIndex);
        for (int i = 0; i < recoveryLegs.size(); i++) {
            LegRow leg = recoveryLegs.get(i);
            jdbcTemplate.update(
                    "INSERT INTO bag_itinerary (bag_tag, seq, leg_id, origin, destination)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    bagTag, nextLegIndex + i, leg.legId(), leg.origin(), leg.destination());
        }
    }

    /** 在持有行李行锁后追加错装事件，seq 按该行李已有事件数递增。 */
    private void insertEvent(String bagTag, String eventType, String legId,
                             String location, OffsetDateTime eventTime) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_tag, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, legId, location, eventTime);
    }

    private boolean hasOpenIncident(String bagTag) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM misload_item mi JOIN misload_incident inc"
                        + " ON mi.incident_key = inc.incident_key"
                        + " WHERE mi.bag_tag = ? AND inc.status = ?",
                Integer.class, bagTag, INCIDENT_OPEN);
        return count != null && count > 0;
    }

    private IncidentRow findIncident(String incidentKey) {
        List<IncidentRow> rows = jdbcTemplate.query(
                "SELECT incident_key, actual_leg_id, arrival_station, status, created_at, closed_at"
                        + " FROM misload_incident WHERE incident_key = ?",
                INCIDENT_MAPPER, incidentKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IncidentRow lockIncident(String incidentKey) {
        List<IncidentRow> rows = jdbcTemplate.query(
                "SELECT incident_key, actual_leg_id, arrival_station, status, created_at, closed_at"
                        + " FROM misload_incident WHERE incident_key = ? FOR UPDATE",
                INCIDENT_MAPPER, incidentKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<MisloadItemRow> findItems(String incidentKey) {
        return jdbcTemplate.query(
                "SELECT incident_key, bag_tag, bag_version, scan_station, created_at"
                        + " FROM misload_item WHERE incident_key = ? ORDER BY bag_tag",
                ITEM_MAPPER, incidentKey);
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, loaded_leg_id,"
                        + " short_leg_id, short_destination, short_registered_at, version, path_generation"
                        + " FROM bag WHERE bag_tag = ? FOR UPDATE",
                (rs, rowNum) -> new BagRow(rs.getString("bag_tag"), rs.getString("current_location"),
                        rs.getInt("next_leg_index"), rs.getString("status"),
                        rs.getString("loaded_leg_id"), rs.getInt("version"),
                        rs.getInt("path_generation")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ItineraryRow> findItinerary(String bagTag) {
        return jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination FROM bag_itinerary"
                        + " WHERE bag_tag = ? ORDER BY seq",
                (rs, rowNum) -> new ItineraryRow(rs.getInt("seq"), rs.getString("leg_id"),
                        rs.getString("origin"), rs.getString("destination")),
                bagTag);
    }

    private LegRow findLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest,"
                        + " arrival_type, arrival_actual, departure_time FROM leg WHERE leg_id = ?",
                LEG_ROW_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 锁定航段行，供确认改派时与装载/封舱互斥。 */
    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(
                "SELECT leg_id, origin, destination, status, version, sealed_manifest,"
                        + " arrival_type, arrival_actual, departure_time FROM leg WHERE leg_id = ?"
                        + " FOR UPDATE",
                LEG_ROW_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static final RowMapper<LegRow> LEG_ROW_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), getInstant(rs, "departure_time"));

    private ReroutePreviewItem toPreviewItem(PlanRow plan) {
        return new ReroutePreviewItem(plan.bagTag(), plan.bagVersion(), plan.currentStation(),
                plan.finalDestination(), readJsonList(plan.originalRemaining()),
                readJsonList(plan.recoveryPath()));
    }

    private OffsetDateTime nowOffset() {
        return OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant getInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("路径序列化失败", ex);
        }
    }

    private List<String> readJsonList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            throw new IllegalStateException("路径反序列化失败", ex);
        }
    }

    private record IncidentRow(String incidentKey, String actualLegId, String arrivalStation,
                               String status, Instant createdAt, Instant closedAt) {
    }

    private record MisloadItemRow(String incidentKey, String bagTag, int bagVersion,
                                  String scanStation, Instant createdAt) {
    }

    private record PlanRow(String incidentKey, String bagTag, int bagVersion,
                           String currentStation, String finalDestination,
                           String originalRemaining, String recoveryPath, Instant createdAt) {
    }

    private record PathSnapshotRow(String bagTag, int generation, String pathKind, int seq,
                                   String legId, String origin, String destination,
                                   Instant createdAt) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, String loadedLegId, int version, int pathGeneration) {
    }

    private record ItineraryRow(int seq, String legId, String origin, String destination) {
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, Instant departureTime) {
    }

    private record RegisterBagRow(String bagTag, int expectedVersion, String scanStation) {
    }

    /** 登记幂等摘要：逐件 bagTag 集合顺序不敏感，但每件版本与扫描站有意义。 */
    private record RegisterPayload(String incidentKey, String actualLegId,
                                   List<RegisterBagPayload> bags) {
    }

    private record RegisterBagPayload(String bagTag, int expectedVersion, String scanStation)
            implements Comparable<RegisterBagPayload> {
        @Override
        public int compareTo(RegisterBagPayload other) {
            return this.bagTag.compareTo(other.bagTag);
        }
    }

    /** 预览幂等摘要：逐件路径顺序有意义，行李集合顺序不敏感。 */
    private record PreviewPayload(String incidentKey, List<PathPayload> bags) {
    }

    private record PathPayload(String bagTag, List<String> path) implements Comparable<PathPayload> {
        @Override
        public int compareTo(PathPayload other) {
            return this.bagTag.compareTo(other.bagTag);
        }
    }

    private record ConfirmPayload(String incidentKey) {
    }
}
