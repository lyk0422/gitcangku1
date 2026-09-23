package com.example.starter.baggage;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

// 无 JSON 字段需要序列化：错装提案与快照均以关系行存储，逐段冻结与血缘可直接 SQL 查询。
import com.example.starter.baggage.BaggageDtos.ConfirmedBagView;
import com.example.starter.baggage.BaggageDtos.MisloadBagItem;
import com.example.starter.baggage.BaggageDtos.MisloadConfirmRequest;
import com.example.starter.baggage.BaggageDtos.MisloadConfirmResponse;
import com.example.starter.baggage.BaggageDtos.MisloadIncidentResponse;
import com.example.starter.baggage.BaggageDtos.MisloadItemView;
import com.example.starter.baggage.BaggageDtos.MisloadPreviewRequest;
import com.example.starter.baggage.BaggageDtos.MisloadPreviewResponse;
import com.example.starter.baggage.BaggageDtos.MisloadRecoveryItem;
import com.example.starter.baggage.BaggageDtos.MisloadRegisterRequest;
import com.example.starter.baggage.BaggageDtos.MisloadRegisterResponse;
import com.example.starter.baggage.BaggageDtos.PathSegmentView;
import com.example.starter.baggage.BaggageDtos.PathSnapshotView;

/**
 * 错装行李批次追回与剩余路径原子改派核心业务。
 *
 * <p>登记：到达站以 incidentKey 登记 2~50 件在同一实际航段到达但该航段不属于各自行程的行李，
 * 每件提交当前版本与扫描站点；任一已交付、已在途（已装机）、已有未结错装或站点不符，
 * 整单 409/422 且无行李改态。
 *
 * <p>预览：调度员逐件提交 1~5 段恢复路径，段间站点连续、出发时间严格递增、首尾精确匹配，
 * 预览冻结行李版本、原剩余路径与恢复路径。
 *
 * <p>确认：重新校验全部行李状态和路径，任一恢复段已封舱则整单失败；成功后在同一事务内
 * 关闭事件、替换各自剩余路径、推进路径代次并保存原/新路径不可变快照。
 *
 * <p>所有写操作经 {@link IdempotencyService} 去重：行李集合换序同参、各自路径顺序有意义；
 * 事件、行李、航段均以 SELECT ... FOR UPDATE 行锁串行化与装载/封舱/补到/到达的并发冲突。
 */
@Service
public class MisloadService {

    private static final String INCIDENT_OPEN = "OPEN";
    private static final String INCIDENT_CONFIRMED = "CONFIRMED";
    private static final String BAG_MISLOADED = "MISLOADED";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_DELIVERED = "DELIVERED";
    private static final String LEG_OPEN = "OPEN";
    private static final String SNAPSHOT_ORIGINAL = "ORIGINAL";
    private static final String SNAPSHOT_NEW = "NEW";
    private static final String EVT_MISLOADED = "MISLOADED";
    private static final String EVT_REROUTED = "REROUTED";

    private static final RowMapper<IncidentRow> INCIDENT_MAPPER = (rs, rowNum) -> new IncidentRow(
            rs.getString("incident_id"), rs.getString("actual_leg_id"),
            rs.getString("scan_station"), rs.getString("status"), rs.getInt("generation"),
            BaggageService.readInstant(rs, "registered_at"),
            BaggageService.readInstant(rs, "confirmed_at"));

    private static final RowMapper<LegRow> LEG_MAPPER = (rs, rowNum) -> new LegRow(
            rs.getString("leg_id"), rs.getString("origin"), rs.getString("destination"),
            rs.getString("status"), BaggageService.readInstant(rs, "departure_at"));

    private static final RowMapper<BagRow> BAG_MAPPER = (rs, rowNum) -> new BagRow(
            rs.getString("bag_tag"), rs.getString("current_location"),
            rs.getInt("next_leg_index"), rs.getString("status"), rs.getInt("version"),
            rs.getInt("path_generation"), rs.getString("open_incident_id"),
            rs.getString("loaded_leg_id"), rs.getString("short_leg_id"));

    private static final RowMapper<PathRow> PATH_MAPPER = (rs, rowNum) -> new PathRow(
            rs.getInt("seq"), rs.getString("leg_id"), rs.getString("origin"),
            rs.getString("destination"), BaggageService.readInstant(rs, "departure_at"));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, rowNum) -> new ItemRow(
            rs.getString("bag_tag"), rs.getInt("frozen_version"), rs.getString("scan_station"));

    private final JdbcTemplate jdbcTemplate;
    private final IdempotencyService idempotencyService;
    /** 可控时钟，默认系统 UTC 时钟，测试可替换。 */
    private Supplier<Instant> clock = Instant::now;

    public MisloadService(JdbcTemplate jdbcTemplate,
                          IdempotencyService idempotencyService) {
        this.jdbcTemplate = jdbcTemplate;
        this.idempotencyService = idempotencyService;
    }

    /** 替换时钟（测试使用），所有登记/确认时刻均取该时钟的 UTC 时刻。 */
    void setClock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    /** 错装批次登记：整单原子，任一件不满足则无行李改态。 */
    public MisloadRegisterResponse registerMisload(MisloadRegisterRequest request) {
        List<RegisterBagParam> normalized = request.bags().stream()
                .map(item -> new RegisterBagParam(item.bagTag(), item.expectedVersion(), item.scanStation()))
                .sorted(Comparator.comparing(RegisterBagParam::bagTag))
                .toList();
        RegisterPayload payload = new RegisterPayload(
                request.incidentKey(), request.actualLegId(), normalized);
        return idempotencyService.execute(request.requestId(), "REGISTER_MISLOAD", 201,
                payload, MisloadRegisterResponse.class, () -> doRegisterMisload(request));
    }

    /** 恢复路径预览：冻结行李版本、原剩余路径及恢复路径；重复预览整体覆盖旧提案。 */
    public MisloadPreviewResponse previewMisload(String incidentKey, MisloadPreviewRequest request) {
        List<RecoveryParam> normalized = request.items().stream()
                .map(item -> new RecoveryParam(item.bagTag(),
                        item.segments().stream().map(seg -> seg.legId()).toList()))
                .sorted(Comparator.comparing(RecoveryParam::bagTag))
                .toList();
        PreviewPayload payload = new PreviewPayload(incidentKey, normalized);
        return idempotencyService.execute(request.requestId(), "PREVIEW_MISLOAD", 200,
                payload, MisloadPreviewResponse.class,
                () -> doPreviewMisload(incidentKey, request));
    }

    /** 改派确认：重新校验全部行李状态和路径，原子关闭事件并替换剩余路径。 */
    public MisloadConfirmResponse confirmMisload(String incidentKey, MisloadConfirmRequest request) {
        return idempotencyService.execute(request.requestId(), "CONFIRM_MISLOAD", 200,
                new ConfirmPayload(incidentKey), MisloadConfirmResponse.class,
                () -> doConfirmMisload(incidentKey));
    }

    /** 错装批次查询：批次头、逐件冻结与不可变路径血缘，只读。 */
    public MisloadIncidentResponse getIncident(String incidentKey) {
        IncidentRow incident = findIncident(incidentKey);
        if (incident == null) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        List<ItemRow> items = jdbcTemplate.query(
                "SELECT bag_tag, frozen_version, scan_station FROM misload_item"
                        + " WHERE incident_id = ? ORDER BY bag_tag",
                ITEM_MAPPER, incidentKey);
        List<MisloadItemView> views = new ArrayList<>();
        for (ItemRow item : items) {
            views.add(toItemView(incident, item.bagTag(), item.frozenVersion(), item.scanStation()));
        }
        return new MisloadIncidentResponse(incident.incidentId(), incident.actualLegId(),
                incident.scanStation(), incident.status(), incident.generation(),
                instantToString(incident.registeredAt()), instantToString(incident.confirmedAt()),
                views, listSnapshots(incidentKey));
    }

    private MisloadRegisterResponse doRegisterMisload(MisloadRegisterRequest request) {
        String incidentKey = request.incidentKey();
        if (findIncident(incidentKey) != null) {
            throw ApiException.conflict("错装事件已存在: " + incidentKey);
        }
        LegRow actualLeg = lockLeg(request.actualLegId());
        List<MisloadBagItem> bags = request.bags();
        Set<String> bagTags = new HashSet<>();
        for (MisloadBagItem item : bags) {
            if (!bagTags.add(item.bagTag())) {
                throw ApiException.unprocessable("错装批次内 bagTag 不得重复: " + item.bagTag());
            }
        }
        // 先整单校验全部行李，任一不满足即抛异常，事务回滚后无行李改态
        List<BagRow> lockedBags = new ArrayList<>();
        for (MisloadBagItem item : bags.stream()
                .sorted(Comparator.comparing(MisloadBagItem::bagTag)).toList()) {
            if (!actualLeg.destination().equals(item.scanStation())) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 扫描站点 " + item.scanStation()
                                + " 与实际航段到达站 " + actualLeg.destination() + " 不符");
            }
            BagRow bag = lockBag(item.bagTag());
            if (bag == null) {
                throw ApiException.unprocessable("行李不存在: " + item.bagTag());
            }
            if (bag.version() != item.expectedVersion()) {
                throw ApiException.conflict(
                        "行李 " + item.bagTag() + " 版本冲突: 期望 " + item.expectedVersion()
                                + "，当前 " + bag.version());
            }
            if (BAG_DELIVERED.equals(bag.status())) {
                throw ApiException.conflict("行李 " + item.bagTag() + " 已交付，不能登记错装");
            }
            if (bag.loadedLegId() != null) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 已在途（装载于航段 " + bag.loadedLegId() + "），不能登记错装");
            }
            if (bag.openIncidentId() != null) {
                throw ApiException.conflict(
                        "行李 " + item.bagTag() + " 已有未结错装事件 " + bag.openIncidentId());
            }
            if (itineraryContainsLeg(item.bagTag(), bag.pathGeneration(), actualLeg.legId())) {
                throw ApiException.unprocessable(
                        "实际航段 " + actualLeg.legId() + " 属于行李 " + item.bagTag() + " 当前行程，不构成错装");
            }
            lockedBags.add(bag);
        }

        OffsetDateTime registeredAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        try {
            jdbcTemplate.update(
                    "INSERT INTO misload_incident (incident_id, actual_leg_id, scan_station, status,"
                            + " generation, registered_at, confirmed_at) VALUES (?, ?, ?, ?, 0, ?, NULL)",
                    incidentKey, actualLeg.legId(), actualLeg.destination(), INCIDENT_OPEN, registeredAt);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            // 主键兜底：并发登记同一 incidentKey 时后提交者整单失败（409），无行李改态
            throw ApiException.conflict("错装事件已存在: " + incidentKey);
        }
        for (BagRow bag : lockedBags) {
            jdbcTemplate.update(
                    "UPDATE bag SET status = ?, current_location = ?, open_incident_id = ?,"
                            + " loaded_leg_id = NULL, short_leg_id = NULL, short_destination = NULL,"
                            + " short_registered_at = NULL, version = version + 1 WHERE bag_tag = ?",
                    BAG_MISLOADED, actualLeg.destination(), incidentKey, bag.bagTag());
            BagRow updated = requireBag(bag.bagTag());
            jdbcTemplate.update(
                    "INSERT INTO misload_item (incident_id, bag_tag, frozen_version, scan_station)"
                            + " VALUES (?, ?, ?, ?)",
                    incidentKey, bag.bagTag(), updated.version(), actualLeg.destination());
            insertEvent(bag.bagTag(), EVT_MISLOADED, actualLeg.legId(), actualLeg.destination());
        }
        IncidentRow incident = requireIncident(incidentKey);
        List<MisloadItemView> views = new ArrayList<>();
        for (BagRow bag : lockedBags) {
            BagRow updated = requireBag(bag.bagTag());
            views.add(toItemView(incident, updated.bagTag(), updated.version(),
                    actualLeg.destination()));
        }
        return new MisloadRegisterResponse(incidentKey, actualLeg.legId(), actualLeg.destination(),
                INCIDENT_OPEN, registeredAt.toInstant().toString(), views);
    }

    private MisloadPreviewResponse doPreviewMisload(String incidentKey, MisloadPreviewRequest request) {
        IncidentRow incident = lockIncident(incidentKey);
        if (!INCIDENT_OPEN.equals(incident.status())) {
            throw ApiException.conflict("错装事件 " + incidentKey + " 已确认关闭，不能再预览");
        }
        List<ItemRow> registered = jdbcTemplate.query(
                "SELECT bag_tag, frozen_version, scan_station FROM misload_item"
                        + " WHERE incident_id = ? ORDER BY bag_tag",
                ITEM_MAPPER, incidentKey);
        Map<String, List<String>> submitted = new LinkedHashMap<>();
        for (MisloadRecoveryItem item : request.items()) {
            if (submitted.put(item.bagTag(),
                    item.segments().stream().map(seg -> seg.legId()).toList()) != null) {
                throw ApiException.unprocessable("恢复路径明细内 bagTag 不得重复: " + item.bagTag());
            }
        }
        if (submitted.size() != registered.size()
                || !submitted.keySet().equals(new HashSet<>(registered.stream().map(ItemRow::bagTag).toList()))) {
            throw ApiException.unprocessable("恢复路径必须逐件覆盖错装批次的全部行李");
        }

        List<ProposalBag> proposals = new ArrayList<>();
        for (ItemRow item : registered) {
            BagRow bag = lockBag(item.bagTag());
            if (bag == null
                    || !incidentKey.equals(bag.openIncidentId())
                    || !BAG_MISLOADED.equals(bag.status())
                    || bag.version() != item.frozenVersion()) {
                throw ApiException.conflict(
                        "行李 " + item.bagTag() + " 状态或版本已变化，预览拒绝");
            }
            List<String> legIds = submitted.get(item.bagTag());
            if (new HashSet<>(legIds).size() != legIds.size()) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 恢复路径航段不得重复");
            }
            List<PathRow> resolved = new ArrayList<>();
            for (String legId : legIds) {
                LegRow leg = findLeg(legId);
                if (leg == null) {
                    throw ApiException.unprocessable("恢复航段不存在: " + legId);
                }
                if (leg.departureAt() == null) {
                    throw ApiException.unprocessable(
                            "恢复航段 " + legId + " 未登记出发时刻，不能用于恢复路径");
                }
                resolved.add(new PathRow(resolved.size(), leg.legId(), leg.origin(),
                        leg.destination(), leg.departureAt()));
            }
            validateRecoveryShape(bag, resolved);
            proposals.add(new ProposalBag(item.bagTag(), resolved));
        }

        jdbcTemplate.update("DELETE FROM misload_proposal WHERE incident_id = ?", incidentKey);
        for (ProposalBag proposal : proposals) {
            for (PathRow segment : proposal.segments()) {
                jdbcTemplate.update(
                        "INSERT INTO misload_proposal (incident_id, bag_tag, seq, leg_id, origin,"
                                + " destination, departure_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        incidentKey, proposal.bagTag(), segment.seq(), segment.legId(),
                        segment.origin(), segment.destination(),
                        OffsetDateTime.ofInstant(segment.departureAt(), ZoneOffset.UTC));
            }
        }
        List<MisloadItemView> views = new ArrayList<>();
        for (ItemRow item : registered) {
            views.add(toItemView(incident, item.bagTag(), item.frozenVersion(), item.scanStation()));
        }
        return new MisloadPreviewResponse(incidentKey, INCIDENT_OPEN, views);
    }

    private MisloadConfirmResponse doConfirmMisload(String incidentKey) {
        IncidentRow incident = lockIncident(incidentKey);
        if (!INCIDENT_OPEN.equals(incident.status())) {
            throw ApiException.conflict("错装事件 " + incidentKey + " 已确认关闭");
        }
        List<ItemRow> items = jdbcTemplate.query(
                "SELECT bag_tag, frozen_version, scan_station FROM misload_item"
                        + " WHERE incident_id = ? ORDER BY bag_tag",
                ITEM_MAPPER, incidentKey);

        // 收集并锁定全部恢复路径航段（按 legId 排序加锁，避免与封舱并发死锁）
        List<ProposalBag> proposals = new ArrayList<>();
        Set<String> recoveryLegIds = new HashSet<>();
        for (ItemRow item : items) {
            List<PathRow> segments = jdbcTemplate.query(
                    "SELECT seq, leg_id, origin, destination, departure_at FROM misload_proposal"
                            + " WHERE incident_id = ? AND bag_tag = ? ORDER BY seq",
                    PATH_MAPPER, incidentKey, item.bagTag());
            if (segments.isEmpty()) {
                throw ApiException.unprocessable(
                        "行李 " + item.bagTag() + " 尚未预览恢复路径，不能确认");
            }
            proposals.add(new ProposalBag(item.bagTag(), segments));
            segments.forEach(segment -> recoveryLegIds.add(segment.legId()));
        }
        Map<String, LegRow> lockedLegs = new LinkedHashMap<>();
        for (String legId : recoveryLegIds.stream().sorted().toList()) {
            lockedLegs.put(legId, lockLeg(legId));
        }

        OffsetDateTime confirmedAt = OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC);
        List<ConfirmedBagView> confirmedBags = new ArrayList<>();
        int maxGeneration = 0;
        for (ItemRow item : items) {
            BagRow bag = lockBag(item.bagTag());
            if (bag == null
                    || !incidentKey.equals(bag.openIncidentId())
                    || !BAG_MISLOADED.equals(bag.status())
                    || bag.version() != item.frozenVersion()) {
                throw ApiException.conflict(
                        "行李 " + item.bagTag() + " 状态或版本已变化，整次改派拒绝");
            }
            ProposalBag proposal = proposals.stream()
                    .filter(p -> p.bagTag().equals(item.bagTag())).findFirst().orElseThrow();
            for (PathRow segment : proposal.segments()) {
                LegRow currentLeg = lockedLegs.get(segment.legId());
                if (!LEG_OPEN.equals(currentLeg.status())) {
                    throw ApiException.unprocessable(
                            "恢复航段 " + segment.legId() + " 已封舱（" + currentLeg.status()
                                    + "），整次改派拒绝");
                }
            }
            // 以提案冻结值重新校验路径形状及首尾精确匹配
            validateRecoveryShape(bag, proposal.segments());

            // 逐件推进路径代次（各行李历史代次可能不同）
            int bagNewGeneration = bag.pathGeneration() + 1;
            maxGeneration = Math.max(maxGeneration, bagNewGeneration);

            // 原剩余路径不可变快照（当前代次中尚未走完的部分，含历史时刻缺失为 NULL）
            List<PathRow> remaining = remainingItinerary(bag.bagTag(), bag.pathGeneration(),
                    bag.nextLegIndex());
            insertSnapshots(incidentKey, bag.bagTag(), SNAPSHOT_ORIGINAL, bag.pathGeneration(), remaining);
            insertSnapshots(incidentKey, bag.bagTag(), SNAPSHOT_NEW, bagNewGeneration, proposal.segments());
            for (PathRow segment : proposal.segments()) {
                jdbcTemplate.update(
                        "INSERT INTO bag_itinerary (bag_tag, generation, seq, leg_id, origin, destination)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        bag.bagTag(), bagNewGeneration, segment.seq(), segment.legId(),
                        segment.origin(), segment.destination());
            }
            jdbcTemplate.update(
                    "UPDATE bag SET path_generation = ?, next_leg_index = 0, status = ?,"
                            + " open_incident_id = NULL, version = version + 1 WHERE bag_tag = ?",
                    bagNewGeneration, BAG_IN_TRANSIT, bag.bagTag());
            jdbcTemplate.update(
                    "UPDATE misload_item SET new_generation = ? WHERE incident_id = ? AND bag_tag = ?",
                    bagNewGeneration, incidentKey, bag.bagTag());
            insertEvent(bag.bagTag(), EVT_REROUTED, null, bag.currentLocation());
            confirmedBags.add(new ConfirmedBagView(bag.bagTag(), BAG_IN_TRANSIT,
                    bag.currentLocation(), bagNewGeneration, 0, toPathViews(proposal.segments())));
        }

        jdbcTemplate.update(
                "UPDATE misload_incident SET status = ?, generation = ?, confirmed_at = ?"
                        + " WHERE incident_id = ?",
                INCIDENT_CONFIRMED, maxGeneration, confirmedAt, incidentKey);
        return new MisloadConfirmResponse(incidentKey, INCIDENT_CONFIRMED, maxGeneration,
                confirmedAt.toInstant().toString(), confirmedBags);
    }

    /** 恢复路径形状校验：段间站点连续、出发时间严格递增、首段起点为当前站、末段终点为原最终目的地。 */
    private void validateRecoveryShape(BagRow bag, List<PathRow> segments) {
        String bagTag = bag.bagTag();
        for (int i = 0; i + 1 < segments.size(); i++) {
            PathRow current = segments.get(i);
            PathRow next = segments.get(i + 1);
            if (!current.destination().equals(next.origin())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 恢复路径段间站点不连续: " + current.legId()
                                + " -> " + next.legId());
            }
            if (!current.departureAt().isBefore(next.departureAt())) {
                throw ApiException.unprocessable(
                        "行李 " + bagTag + " 恢复路径出发时间必须严格递增: " + current.legId()
                                + " -> " + next.legId());
            }
        }
        PathRow first = segments.get(0);
        if (!first.origin().equals(bag.currentLocation())) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 恢复路径首段起点 " + first.origin()
                            + " 与当前站 " + bag.currentLocation() + " 不匹配");
        }
        PathRow last = segments.get(segments.size() - 1);
        String originalFinalDestination = originalFinalDestination(bagTag);
        if (!last.destination().equals(originalFinalDestination)) {
            throw ApiException.unprocessable(
                    "行李 " + bagTag + " 恢复路径末段终点 " + last.destination()
                            + " 与原最终目的地 " + originalFinalDestination + " 不匹配");
        }
    }

    private void insertSnapshots(String incidentKey, String bagTag, String kind,
                                 int generation, List<PathRow> path) {
        for (PathRow segment : path) {
            jdbcTemplate.update(
                    "INSERT INTO bag_path_snapshot (incident_id, bag_tag, snapshot_kind, generation,"
                            + " seq, leg_id, origin, destination, departure_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    incidentKey, bagTag, kind, generation, segment.seq(), segment.legId(),
                    segment.origin(), segment.destination(),
                    segment.departureAt() == null ? null
                            : OffsetDateTime.ofInstant(segment.departureAt(), ZoneOffset.UTC));
        }
    }

    /**
     * 组装逐件视图：OPEN 时原剩余路径取当前代次未走完部分、恢复路径取提案（未预览为空）；
     * CONFIRMED 后两者分别取 ORIGINAL/NEW 不可变快照。
     */
    private MisloadItemView toItemView(IncidentRow incident, String bagTag,
                                       int frozenVersion, String scanStation) {
        BagRow bag = requireBag(bagTag);
        String finalDestination = originalFinalDestination(bagTag);
        List<PathSegmentView> originalRemaining;
        List<PathSegmentView> recoveryPath;
        if (INCIDENT_CONFIRMED.equals(incident.status())) {
            originalRemaining = toPathViews(snapshotPath(incident.incidentId(), bagTag, SNAPSHOT_ORIGINAL));
            recoveryPath = toPathViews(snapshotPath(incident.incidentId(), bagTag, SNAPSHOT_NEW));
        } else {
            originalRemaining = toPathViews(remainingItinerary(bagTag, bag.pathGeneration(),
                    bag.nextLegIndex()));
            recoveryPath = toPathViews(jdbcTemplate.query(
                    "SELECT seq, leg_id, origin, destination, departure_at FROM misload_proposal"
                            + " WHERE incident_id = ? AND bag_tag = ? ORDER BY seq",
                    PATH_MAPPER, incident.incidentId(), bagTag));
        }
        return new MisloadItemView(bagTag, frozenVersion, scanStation, bag.pathGeneration(),
                bag.currentLocation(), finalDestination, originalRemaining, recoveryPath);
    }

    private List<PathSnapshotView> listSnapshots(String incidentKey) {
        List<String> bagTags = jdbcTemplate.queryForList(
                "SELECT DISTINCT bag_tag FROM bag_path_snapshot WHERE incident_id = ? ORDER BY bag_tag",
                String.class, incidentKey);
        List<PathSnapshotView> views = new ArrayList<>();
        for (String bagTag : bagTags) {
            for (String kind : List.of(SNAPSHOT_ORIGINAL, SNAPSHOT_NEW)) {
                List<PathRow> path = snapshotPath(incidentKey, bagTag, kind);
                if (!path.isEmpty()) {
                    int generation = jdbcTemplate.queryForObject(
                            "SELECT generation FROM bag_path_snapshot WHERE incident_id = ?"
                                    + " AND bag_tag = ? AND snapshot_kind = ? FETCH FIRST 1 ROW ONLY",
                            Integer.class, incidentKey, bagTag, kind);
                    views.add(new PathSnapshotView(bagTag, kind, generation, toPathViews(path)));
                }
            }
        }
        return views;
    }

    private List<PathRow> snapshotPath(String incidentKey, String bagTag, String kind) {
        return jdbcTemplate.query(
                "SELECT seq, leg_id, origin, destination, departure_at FROM bag_path_snapshot"
                        + " WHERE incident_id = ? AND bag_tag = ? AND snapshot_kind = ? ORDER BY seq",
                PATH_MAPPER, incidentKey, bagTag, kind);
    }

    private List<PathRow> remainingItinerary(String bagTag, int generation, int fromSeq) {
        return jdbcTemplate.query(
                "SELECT i.seq AS seq, i.leg_id AS leg_id, i.origin AS origin, i.destination AS destination,"
                        + " l.departure_at AS departure_at FROM bag_itinerary i"
                        + " LEFT JOIN leg l ON l.leg_id = i.leg_id"
                        + " WHERE i.bag_tag = ? AND i.generation = ? AND i.seq >= ? ORDER BY i.seq",
                PATH_MAPPER, bagTag, generation, fromSeq);
    }

    private boolean itineraryContainsLeg(String bagTag, int generation, String legId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE bag_tag = ? AND generation = ? AND leg_id = ?",
                Integer.class, bagTag, generation, legId);
        return count != null && count > 0;
    }

    /** 原最终目的地：始终取 generation 0（原始行程）末段到达站。 */
    private String originalFinalDestination(String bagTag) {
        List<String> destinations = jdbcTemplate.queryForList(
                "SELECT destination FROM bag_itinerary WHERE bag_tag = ? AND generation = 0"
                        + " ORDER BY seq DESC FETCH FIRST 1 ROW ONLY",
                String.class, bagTag);
        if (destinations.isEmpty()) {
            throw ApiException.unprocessable("行李 " + bagTag + " 缺少原始行程");
        }
        return destinations.get(0);
    }

    private IncidentRow findIncident(String incidentKey) {
        List<IncidentRow> rows = jdbcTemplate.query(incidentSelect(false), INCIDENT_MAPPER, incidentKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private IncidentRow lockIncident(String incidentKey) {
        List<IncidentRow> rows = jdbcTemplate.query(incidentSelect(true), INCIDENT_MAPPER, incidentKey);
        if (rows.isEmpty()) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        return rows.get(0);
    }

    private IncidentRow requireIncident(String incidentKey) {
        IncidentRow incident = findIncident(incidentKey);
        if (incident == null) {
            throw ApiException.notFound("错装事件不存在: " + incidentKey);
        }
        return incident;
    }

    private static String incidentSelect(boolean forUpdate) {
        return "SELECT incident_id, actual_leg_id, scan_station, status, generation,"
                + " registered_at, confirmed_at FROM misload_incident WHERE incident_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private LegRow findLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(legSelect(false), LEG_MAPPER, legId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LegRow lockLeg(String legId) {
        List<LegRow> rows = jdbcTemplate.query(legSelect(true), LEG_MAPPER, legId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private static String legSelect(boolean forUpdate) {
        return "SELECT leg_id, origin, destination, status, departure_at FROM leg WHERE leg_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
    }

    private BagRow lockBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, version, path_generation,"
                        + " open_incident_id, loaded_leg_id, short_leg_id FROM bag WHERE bag_tag = ?"
                        + " FOR UPDATE",
                BAG_MAPPER, bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private BagRow requireBag(String bagTag) {
        List<BagRow> rows = jdbcTemplate.query(
                "SELECT bag_tag, current_location, next_leg_index, status, version, path_generation,"
                        + " open_incident_id, loaded_leg_id, short_leg_id FROM bag WHERE bag_tag = ?",
                BAG_MAPPER, bagTag);
        if (rows.isEmpty()) {
            throw ApiException.notFound("行李不存在: " + bagTag);
        }
        return rows.get(0);
    }

    /** 在持有行李行锁后追加事件，seq 按该行李已有事件数递增。 */
    private void insertEvent(String bagTag, String eventType, String legId, String location) {
        Integer maxSeq = jdbcTemplate.queryForObject(
                "SELECT MAX(seq) FROM bag_event WHERE bag_tag = ?", Integer.class, bagTag);
        int nextSeq = maxSeq == null ? 0 : maxSeq + 1;
        jdbcTemplate.update(
                "INSERT INTO bag_event (bag_tag, seq, event_type, leg_id, location, event_time)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                bagTag, nextSeq, eventType, legId, location,
                OffsetDateTime.ofInstant(clock.get(), ZoneOffset.UTC));
    }

    private static List<PathSegmentView> toPathViews(List<PathRow> path) {
        return path.stream()
                .map(row -> new PathSegmentView(row.seq(), row.legId(), row.origin(),
                        row.destination(), instantToString(row.departureAt())))
                .toList();
    }

    private static String instantToString(Instant value) {
        return value == null ? null : value.toString();
    }

    private record IncidentRow(String incidentId, String actualLegId, String scanStation,
                               String status, int generation, Instant registeredAt,
                               Instant confirmedAt) {
    }

    private record LegRow(String legId, String origin, String destination,
                          String status, Instant departureAt) {
    }

    private record BagRow(String bagTag, String currentLocation, int nextLegIndex,
                          String status, int version, int pathGeneration,
                          String openIncidentId, String loadedLegId, String shortLegId) {
    }

    private record PathRow(int seq, String legId, String origin, String destination,
                           Instant departureAt) {
    }

    private record ItemRow(String bagTag, int frozenVersion, String scanStation) {
    }

    private record ProposalBag(String bagTag, List<PathRow> segments) {
    }

    /** 登记幂等摘要参数：bags 已按 bagTag 排序，集合换序不视为异参。 */
    private record RegisterBagParam(String bagTag, int expectedVersion, String scanStation) {
    }

    private record RegisterPayload(String incidentKey, String actualLegId,
                                   List<RegisterBagParam> bags) {
    }

    /** 预览幂等摘要参数：items 按 bagTag 排序，每件 segments 保持提交顺序（顺序有意义）。 */
    private record RecoveryParam(String bagTag, List<String> legIds) {
    }

    private record PreviewPayload(String incidentKey, List<RecoveryParam> items) {
    }

    private record ConfirmPayload(String incidentKey) {
    }
}
