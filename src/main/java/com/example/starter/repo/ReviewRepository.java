package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.TimeWindow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审核结果与幂等去重数据访问。
 * 点快照以 "x,y;x,y" 文本保存，命中 zoneId 以逗号拼接；
 * 航线窗口以 "start,end" 保存（空串为全时），命中区域窗口以 "start:end;..." 保存
 * （空项为该区域全时，与命中 zoneId 按序对齐），均为不可变内容。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String REVIEW_COLUMNS =
            "review_id, route_id, route_version, airspace_version, conclusion, "
                    + "hit_zone_ids, points_snapshot, route_window, zone_windows, "
                    + "request_id, created_at";

    private static final RowMapper<ReviewPo> REVIEW_MAPPER = (rs, n) -> new ReviewPo(
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("conclusion"),
            decodeZoneIds(rs.getString("hit_zone_ids")),
            decodePoints(rs.getString("points_snapshot")),
            decodeWindow(rs.getString("route_window")),
            decodeWindowList(rs.getString("zone_windows")),
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
                        + "hit_zone_ids, points_snapshot, route_window, zone_windows, "
                        + "request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.conclusion(), encodeZoneIds(po.hitZoneIds()), encodePoints(po.pointsSnapshot()),
                encodeWindow(po.routeWindow()), encodeWindowList(po.zoneWindows()),
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

    /** 单窗口编码："start,end"；全时（null）编码为空串。 */
    public static String encodeWindow(TimeWindow window) {
        return window == null ? "" : window.startUtcMillis() + "," + window.endUtcMillis();
    }

    /** 单窗口解码；空串表示全时（null）。 */
    public static TimeWindow decodeWindow(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] se = text.split(",");
        return new TimeWindow(Long.parseLong(se[0]), Long.parseLong(se[1]));
    }

    /**
     * 窗口列表编码：各项 "start:end"、全时项为空串，分号分隔并保留空项；
     * 与命中 zoneId 按序对齐。空列表编码为空串。
     */
    public static String encodeWindowList(List<TimeWindow> windows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < windows.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            TimeWindow w = windows.get(i);
            if (w != null) {
                sb.append(w.startUtcMillis()).append(':').append(w.endUtcMillis());
            }
        }
        return sb.toString();
    }

    /** 窗口列表解码；空串返回空列表，空项表示该位置全时（null）。 */
    public static List<TimeWindow> decodeWindowList(String text) {
        List<TimeWindow> windows = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            // limit=-1 保留末尾空项，避免全时区域位于末尾时错位
            for (String item : text.split(";", -1)) {
                if (item.isEmpty()) {
                    windows.add(null);
                } else {
                    String[] se = item.split(":");
                    windows.add(new TimeWindow(Long.parseLong(se[0]), Long.parseLong(se[1])));
                }
            }
        }
        return windows;
    }
}
