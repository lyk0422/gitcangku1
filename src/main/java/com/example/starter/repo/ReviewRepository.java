package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.TimeWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审核结果与幂等去重数据访问。
 * 点快照以 "x,y;x,y" 文本保存，命中 zoneId 以逗号拼接；命中区域窗口快照以 JSON 保存，
 * 航线窗口以两列保存，均为不可变内容。
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // 静态解码只依赖 Jackson 默认映射，使用独立 mapper 避免实例依赖
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private ReviewPo mapReview(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ReviewPo(
                rs.getString("review_id"),
                rs.getString("route_id"),
                rs.getInt("route_version"),
                rs.getLong("airspace_version"),
                rs.getString("conclusion"),
                decodeZoneIds(rs.getString("hit_zone_ids")),
                decodeHits(rs.getString("hit_windows_json")),
                decodePoints(rs.getString("points_snapshot")),
                new TimeWindow(
                        (Long) rs.getObject("route_window_start"),
                        (Long) rs.getObject("route_window_end")),
                rs.getString("request_id"),
                rs.getLong("created_at"));
    }

    private static final String REVIEW_COLUMNS =
            "review_id, route_id, route_version, airspace_version, conclusion, "
                    + "hit_zone_ids, hit_windows_json, points_snapshot, "
                    + "route_window_start, route_window_end, request_id, created_at";

    /** 按 reviewId 查询不可变审核记录，不存在返回 null。 */
    public ReviewPo findReview(String reviewId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE review_id = ?",
                this::mapReview, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条审核记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public ReviewPo findLatestReview(String routeId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                this::mapReview, routeId).stream().findFirst().orElse(null);
    }

    /** 插入不可变审核结果（调用方负责事务）。 */
    public void insertReview(ReviewPo po) {
        jdbc.update("INSERT INTO review "
                        + "(review_id, route_id, route_version, airspace_version, conclusion, "
                        + "hit_zone_ids, hit_windows_json, points_snapshot, "
                        + "route_window_start, route_window_end, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.conclusion(), encodeZoneIds(po.hitZoneIds()), encodeHits(po.hits()),
                encodePoints(po.pointsSnapshot()),
                po.routeWindow().startMs(), po.routeWindow().endMs(),
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

    /** 命中项快照编码为 JSON：[{"zoneId":...,"windowStart":...,"windowEnd":...}]。 */
    private String encodeHits(List<ZoneHitPo> hits) {
        try {
            return objectMapper.writeValueAsString(hits);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("命中窗口快照序列化失败", ex);
        }
    }

    /** 命中项快照解码；既有历史缺少该列（NULL）时按无快照项处理，窗口语义另由全时解释。 */
    private static List<ZoneHitPo> decodeHits(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return SHARED_MAPPER.readValue(json, new TypeReference<List<ZoneHitPo>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("命中窗口快照反序列化失败: " + json, ex);
        }
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
}
