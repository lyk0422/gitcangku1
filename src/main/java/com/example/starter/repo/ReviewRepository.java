package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.ZoneWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审核结果与幂等去重数据访问。
 * 点快照以 "x,y;x,y" 文本保存，命中 zoneId 以逗号拼接，
 * 区域窗口快照以 "zoneId:start:end;..." 文本保存（起止为空表示该区全时），均为不可变内容。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String REVIEW_COLUMNS =
            "review_id, route_id, route_version, airspace_version, conclusion, "
                    + "hit_zone_ids, points_snapshot, route_window_start, route_window_end, "
                    + "zone_windows, request_id, created_at";

    private static final RowMapper<ReviewPo> REVIEW_MAPPER = (rs, n) -> new ReviewPo(
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("conclusion"),
            decodeZoneIds(rs.getString("hit_zone_ids")),
            decodePoints(rs.getString("points_snapshot")),
            (Long) rs.getObject("route_window_start"),
            (Long) rs.getObject("route_window_end"),
            decodeZoneWindows(rs.getString("zone_windows")),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 按 reviewId 查询不可变审核记录，不存在返回 null。 */
    public ReviewPo findReview(String reviewId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE review_id = ?",
                REVIEW_MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条审核记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public ReviewPo findLatestReview(String routeId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                REVIEW_MAPPER, routeId).stream().findFirst().orElse(null);
    }

    /** 插入不可变审核结果（调用方负责事务）。 */
    public void insertReview(ReviewPo po) {
        jdbc.update("INSERT INTO review "
                        + "(review_id, route_id, route_version, airspace_version, conclusion, "
                        + "hit_zone_ids, points_snapshot, route_window_start, route_window_end, "
                        + "zone_windows, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.conclusion(), encodeZoneIds(po.hitZoneIds()), encodePoints(po.pointsSnapshot()),
                po.routeWindowStart(), po.routeWindowEnd(), encodeZoneWindows(po.zoneWindows()),
                po.requestId(), po.createdAt());
    }

    /** 查询幂等去重记录，不存在返回 null。 */
    public DedupPo findDedup(String requestId) {
        return jdbc.query(
                "SELECT request_id, request_kind, request_hash, response_json, created_at "
                        + "FROM request_dedup WHERE request_id = ?",
                (rs, n) -> new DedupPo(rs.getString("request_id"), rs.getString("request_kind"),
                        rs.getString("request_hash"), rs.getString("response_json"),
                        rs.getLong("created_at")),
                requestId).stream().findFirst().orElse(null);
    }

    /** 插入幂等去重记录（与业务变更在同一事务提交）。 */
    public void insertDedup(DedupPo po) {
        jdbc.update("INSERT INTO request_dedup "
                        + "(request_id, request_kind, request_hash, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                po.requestId(), po.requestKind(), po.requestHash(), po.responseJson(), po.createdAt());
    }

    /** 点列表编码："x,y;x,y"；空列表编码为空串。 */
    public static String encodePoints(List<Point> points) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(points.get(i).x()).append(',').append(points.get(i).y());
        }
        return sb.toString();
    }

    /** 点列表解码。 */
    public static List<Point> decodePoints(String text) {
        List<Point> points = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String pair : text.split(";")) {
                String[] xy = pair.split(",");
                points.add(new Point(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
            }
        }
        return points;
    }

    /** zoneId 列表编码（字典序去重由调用方保证），逗号拼接。 */
    public static String encodeZoneIds(List<String> zoneIds) {
        return String.join(",", zoneIds);
    }

    /** zoneId 列表解码，空串返回空列表。 */
    public static List<String> decodeZoneIds(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(text.split(",")));
    }

    /**
     * 区域窗口快照编码："zoneId:start:end;..."，起止为空串表示该区全时有效；
     * 空列表编码为空串。与既有 zoneId 逗号拼接一致，假定 zoneId 不含 ':' 与 ';'。
     */
    public static String encodeZoneWindows(List<ZoneWindow> windows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            ZoneWindow w = windows.get(i);
            sb.append(w.zoneId()).append(':');
            if (w.windowStart() != null) {
                sb.append(w.windowStart());
            }
            sb.append(':');
            if (w.windowEnd() != null) {
                sb.append(w.windowEnd());
            }
        }
        return sb.toString();
    }

    /** 区域窗口快照解码；起止为空串解码为 null（全时）。 */
    public static List<ZoneWindow> decodeZoneWindows(String text) {
        List<ZoneWindow> windows = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String entry : text.split(";")) {
                String[] parts = entry.split(":", -1);
                Long start = parts[1].isEmpty() ? null : Long.parseLong(parts[1]);
                Long end = parts[2].isEmpty() ? null : Long.parseLong(parts[2]);
                windows.add(new ZoneWindow(parts[0], start, end));
            }
        }
        return windows;
    }
}
