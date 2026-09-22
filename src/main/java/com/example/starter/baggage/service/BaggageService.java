package com.example.starter.baggage.service;

import com.example.starter.baggage.api.dto.ArrivalRequest;
import com.example.starter.baggage.api.dto.BagView;
import com.example.starter.baggage.api.dto.LegView;
import com.example.starter.baggage.api.dto.LoadRequest;
import com.example.starter.baggage.api.dto.LoadResult;
import com.example.starter.baggage.api.dto.ManifestView;
import com.example.starter.baggage.api.dto.RegisterBagRequest;
import com.example.starter.baggage.api.dto.RegisterLegRequest;
import com.example.starter.baggage.api.dto.SealRequest;
import com.example.starter.baggage.api.dto.TraceView;
import com.example.starter.baggage.error.BusinessException;
import com.example.starter.baggage.error.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 联程行李装载交接核心业务。写方法由 {@link IdempotencyService} 在事务内调用，
 * 业务校验失败抛出 {@link BusinessException}（422）并整体回滚。
 */
@Service
public class BaggageService {

    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_SEALED = "SEALED";
    private static final String STATUS_ARRIVED = "ARRIVED";
    private static final String BAG_IN_TRANSIT = "IN_TRANSIT";
    private static final String BAG_DELIVERED = "DELIVERED";

    private final JdbcTemplate jdbc;

    public BaggageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 写操作 ----------

    /**
     * 登记航段：legId 唯一，初始状态 OPEN、版本 0。
     */
    public LegView registerLeg(RegisterLegRequest req) {
        try {
            jdbc.update("INSERT INTO leg (leg_id, origin, destination, status, version) VALUES (?, ?, ?, 'OPEN', 0)",
                    req.legId(), req.origin(), req.destination());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("航段已存在: " + req.legId());
        }
        return new LegView(req.legId(), req.origin(), req.destination(), STATUS_OPEN, 0);
    }

    /**
     * 登记行李：1~5 个不重复航段的有序行程，相邻航段首尾站衔接，初始位于首段始发站。
     */
    public BagView registerBag(RegisterBagRequest req) {
        List<String> legIds = req.legIds();
        if (new HashSet<>(legIds).size() != legIds.size()) {
            throw new BusinessException("行程航段不允许重复");
        }
        Map<String, LegRow> legs = legIds.stream().distinct().collect(Collectors.toMap(
                Function.identity(), this::findLegOrThrow));
        for (int i = 0; i + 1 < legIds.size(); i++) {
            LegRow current = legs.get(legIds.get(i));
            LegRow next = legs.get(legIds.get(i + 1));
            if (!current.destination().equals(next.origin())) {
                throw new BusinessException("相邻航段首尾站不衔接: " + current.legId() + " -> " + next.legId());
            }
        }
        LegRow first = legs.get(legIds.get(0));
        try {
            jdbc.update("INSERT INTO bag (bag_tag, status, current_station, next_leg_index, loaded_leg_id, itinerary_size)"
                            + " VALUES (?, 'IN_TRANSIT', ?, 0, NULL, ?)",
                    req.bagTag(), first.origin(), legIds.size());
        } catch (DuplicateKeyException e) {
            throw new BusinessException("行李已存在: " + req.bagTag());
        }
        for (int i = 0; i < legIds.size(); i++) {
            jdbc.update("INSERT INTO bag_itinerary (bag_tag, seq, leg_id) VALUES (?, ?, ?)",
                    req.bagTag(), i, legIds.get(i));
        }
        return new BagView(req.bagTag(), BAG_IN_TRANSIT, first.origin(), 0, legIds.size(), null);
    }

    /**
     * 批量装载：航段须 OPEN 且版本匹配；每件行李须位于始发站、以本航段为待乘航段且未装载。
     * 任一行李不满足则整批失败（抛出异常回滚，无一件移动）。
     */
    public LoadResult load(String legId, LoadRequest req) {
        LegRow leg = lockLegOrThrow(legId);
        if (!STATUS_OPEN.equals(leg.status())) {
            throw new BusinessException("航段非 OPEN，禁止装载: " + legId);
        }
        if (leg.version() != req.expectedVersion()) {
            throw new BusinessException("航段版本不匹配: expected=" + req.expectedVersion() + ", actual=" + leg.version());
        }
        List<String> bagTags = req.bagTags();
        if (new HashSet<>(bagTags).size() != bagTags.size()) {
            throw new BusinessException("单批装载行李牌号不允许重复");
        }
        for (String bagTag : bagTags) {
            BagRow bag = findBag(bagTag);
            if (bag == null) {
                throw new BusinessException("行李不存在: " + bagTag);
            }
            if (!BAG_IN_TRANSIT.equals(bag.status())) {
                throw new BusinessException("行李已完成全部行程，不可装载: " + bagTag);
            }
            if (bag.loadedLegId() != null) {
                throw new BusinessException("行李已在航段 " + bag.loadedLegId() + " 装载: " + bagTag);
            }
            String nextLegId = itineraryLegAt(bagTag, bag.nextLegIndex());
            if (!legId.equals(nextLegId)) {
                throw new BusinessException("航段 " + legId + " 非行李 " + bagTag + " 当前待乘航段");
            }
            if (!leg.origin().equals(bag.currentStation())) {
                throw new BusinessException("行李 " + bagTag + " 不在航段始发站 " + leg.origin());
            }
        }
        for (String bagTag : bagTags) {
            jdbc.update("INSERT INTO load_entry (leg_id, bag_tag) VALUES (?, ?)", legId, bagTag);
            int updated = jdbc.update("UPDATE bag SET loaded_leg_id = ? WHERE bag_tag = ? AND loaded_leg_id IS NULL",
                    legId, bagTag);
            if (updated != 1) {
                throw new BusinessException("行李已在其他航段装载: " + bagTag);
            }
        }
        int newVersion = bumpLegVersion(legId, leg.version(), STATUS_OPEN);
        return new LoadResult(legId, STATUS_OPEN, newVersion, bagTags.size(), List.copyOf(bagTags));
    }

    /**
     * 封舱：校验版本，固化只读封舱清单并转 SEALED；空清单也可封舱。
     */
    public ManifestView seal(String legId, SealRequest req) {
        LegRow leg = lockLegOrThrow(legId);
        if (!STATUS_OPEN.equals(leg.status())) {
            throw new BusinessException("航段非 OPEN，禁止封舱: " + legId);
        }
        if (leg.version() != req.expectedVersion()) {
            throw new BusinessException("航段版本不匹配: expected=" + req.expectedVersion() + ", actual=" + leg.version());
        }
        jdbc.update("INSERT INTO manifest_entry (leg_id, bag_tag) SELECT leg_id, bag_tag FROM load_entry WHERE leg_id = ?",
                legId);
        jdbc.update("DELETE FROM load_entry WHERE leg_id = ?", legId);
        int newVersion = bumpLegVersion(legId, leg.version(), STATUS_SEALED);
        return new ManifestView(legId, STATUS_SEALED, newVersion, manifestTags(legId));
    }

    /**
     * 到达确认：实际袋号集合须与封舱清单完全相同，否则 422 且航段与全部行李不变；
     * 匹配则原子转 ARRIVED，各行李置于到达站并推进待乘索引，完成全部行程者为 DELIVERED。
     */
    public ManifestView arrive(String legId, ArrivalRequest req) {
        LegRow leg = lockLegOrThrow(legId);
        if (!STATUS_SEALED.equals(leg.status())) {
            throw new BusinessException("航段非 SEALED，禁止到达确认: " + legId);
        }
        List<String> manifest = manifestTags(legId);
        Set<String> expected = new HashSet<>(manifest);
        Set<String> actual = new HashSet<>(req.bagTags());
        if (actual.size() != req.bagTags().size() || !expected.equals(actual)) {
            throw new BusinessException("实际袋号集合与封舱清单不一致");
        }
        for (String bagTag : manifest) {
            jdbc.update("UPDATE bag SET current_station = ?, next_leg_index = next_leg_index + 1, loaded_leg_id = NULL,"
                            + " status = CASE WHEN next_leg_index + 1 >= itinerary_size THEN 'DELIVERED' ELSE status END"
                            + " WHERE bag_tag = ?",
                    leg.destination(), bagTag);
        }
        int newVersion = bumpLegVersion(legId, leg.version(), STATUS_ARRIVED);
        return new ManifestView(legId, STATUS_ARRIVED, newVersion, manifest);
    }

    // ---------- 查询 ----------

    /**
     * 查询航段。
     */
    public LegView getLeg(String legId) {
        LegRow leg = findLegOrThrow(legId);
        return new LegView(leg.legId(), leg.origin(), leg.destination(), leg.status(), leg.version());
    }

    /**
     * 查询行李。
     */
    public BagView getBag(String bagTag) {
        BagRow bag = findBagOrThrow(bagTag);
        return new BagView(bag.bagTag(), bag.status(), bag.currentStation(),
                bag.nextLegIndex(), bag.itinerarySize(), bag.loadedLegId());
    }

    /**
     * 行李轨迹：当前位置、状态及各行程段进度。
     */
    public TraceView getTrace(String bagTag) {
        BagRow bag = findBagOrThrow(bagTag);
        List<TraceView.Segment> segments = new ArrayList<>();
        jdbc.query("SELECT i.seq, i.leg_id, l.origin, l.destination FROM bag_itinerary i"
                        + " JOIN leg l ON l.leg_id = i.leg_id WHERE i.bag_tag = ? ORDER BY i.seq",
                rs -> {
                    int seq = rs.getInt("seq");
                    String state = seq < bag.nextLegIndex() ? "COMPLETED"
                            : seq == bag.nextLegIndex() && BAG_IN_TRANSIT.equals(bag.status()) ? "CURRENT" : "PENDING";
                    segments.add(new TraceView.Segment(seq, rs.getString("leg_id"),
                            rs.getString("origin"), rs.getString("destination"), state));
                }, bagTag);
        return new TraceView(bag.bagTag(), bag.status(), bag.currentStation(),
                bag.nextLegIndex(), bag.loadedLegId(), segments);
    }

    /**
     * 清单查询：OPEN 航段返回当前装载明细，SEALED/ARRIVED 返回只读封舱清单。
     */
    public ManifestView getManifest(String legId) {
        LegRow leg = findLegOrThrow(legId);
        List<String> tags = STATUS_OPEN.equals(leg.status())
                ? jdbc.queryForList("SELECT bag_tag FROM load_entry WHERE leg_id = ? ORDER BY bag_tag",
                String.class, legId)
                : manifestTags(legId);
        return new ManifestView(leg.legId(), leg.status(), leg.version(), tags);
    }

    // ---------- 内部方法 ----------

    private LegRow lockLegOrThrow(String legId) {
        List<LegRow> rows = jdbc.query(
                "SELECT leg_id, origin, destination, status, version FROM leg WHERE leg_id = ? FOR UPDATE",
                (rs, i) -> new LegRow(rs.getString("leg_id"), rs.getString("origin"),
                        rs.getString("destination"), rs.getString("status"), rs.getInt("version")),
                legId);
        if (rows.isEmpty()) {
            throw new NotFoundException("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private LegRow findLegOrThrow(String legId) {
        List<LegRow> rows = jdbc.query(
                "SELECT leg_id, origin, destination, status, version FROM leg WHERE leg_id = ?",
                (rs, i) -> new LegRow(rs.getString("leg_id"), rs.getString("origin"),
                        rs.getString("destination"), rs.getString("status"), rs.getInt("version")),
                legId);
        if (rows.isEmpty()) {
            throw new NotFoundException("航段不存在: " + legId);
        }
        return rows.get(0);
    }

    private BagRow findBagOrThrow(String bagTag) {
        BagRow bag = findBag(bagTag);
        if (bag == null) {
            throw new NotFoundException("行李不存在: " + bagTag);
        }
        return bag;
    }

    private BagRow findBag(String bagTag) {
        List<BagRow> rows = jdbc.query(
                "SELECT bag_tag, status, current_station, next_leg_index, loaded_leg_id, itinerary_size"
                        + " FROM bag WHERE bag_tag = ?",
                (rs, i) -> new BagRow(rs.getString("bag_tag"), rs.getString("status"),
                        rs.getString("current_station"), rs.getInt("next_leg_index"),
                        rs.getString("loaded_leg_id"), rs.getInt("itinerary_size")),
                bagTag);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String itineraryLegAt(String bagTag, int seq) {
        List<String> rows = jdbc.queryForList(
                "SELECT leg_id FROM bag_itinerary WHERE bag_tag = ? AND seq = ?", String.class, bagTag, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> manifestTags(String legId) {
        return jdbc.queryForList("SELECT bag_tag FROM manifest_entry WHERE leg_id = ? ORDER BY bag_tag",
                String.class, legId);
    }

    private int bumpLegVersion(String legId, int expectedVersion, String newStatus) {
        int updated = jdbc.update("UPDATE leg SET status = ?, version = version + 1 WHERE leg_id = ? AND version = ?",
                newStatus, legId, expectedVersion);
        if (updated != 1) {
            throw new BusinessException("航段版本并发冲突: " + legId);
        }
        return expectedVersion + 1;
    }

    /**
     * 航段行。
     */
    private record LegRow(String legId, String origin, String destination, String status, int version) {
    }

    /**
     * 行李行。
     */
    private record BagRow(String bagTag, String status, String currentStation,
                          int nextLegIndex, String loadedLegId, int itinerarySize) {
    }
}
