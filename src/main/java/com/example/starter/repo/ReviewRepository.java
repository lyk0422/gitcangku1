package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.QuadraticCurve;
import com.example.starter.domain.RouteKind;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审核结果与幂等去重数据访问。
 *
 * <p>LINE 点快照以 "x,y;x,y" 文本保存；CURVE 曲线段完整控制点快照以 JSON
 * 保存（段数组，每段 3 个 {x,y}），命中 zoneId 以逗号拼接，均为不可变内容。</p>
 */
@Repository
public class ReviewRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private static final String REVIEW_COLUMNS =
            "review_id, route_id, route_kind, route_version, airspace_version, conclusion, "
                    + "hit_zone_ids, points_snapshot, curve_snapshot, request_id, operator, created_at";

    private RowMapper<ReviewPo> reviewMapper() {
        return (rs, n) -> new ReviewPo(
                rs.getString("review_id"),
                rs.getString("route_id"),
                RouteKind.valueOf(rs.getString("route_kind")),
                rs.getInt("route_version"),
                rs.getLong("airspace_version"),
                rs.getString("conclusion"),
                decodeZoneIds(rs.getString("hit_zone_ids")),
                decodePoints(rs.getString("points_snapshot")),
                decodeCurves(rs.getString("curve_snapshot")),
                rs.getString("request_id"),
                rs.getString("operator"),
                rs.getLong("created_at"));
    }

    /** 按 reviewId 查询不可变审核记录，不存在返回 null。 */
    public ReviewPo findReview(String reviewId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE review_id = ?",
                reviewMapper(), reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条审核记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public ReviewPo findLatestReview(String routeId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                reviewMapper(), routeId).stream().findFirst().orElse(null);
    }

    /** 插入不可变审核结果（调用方负责事务）。 */
    public void insertReview(ReviewPo po) {
        jdbc.update("INSERT INTO review "
                        + "(review_id, route_id, route_kind, route_version, airspace_version, "
                        + "conclusion, hit_zone_ids, points_snapshot, curve_snapshot, "
                        + "request_id, operator, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeKind().name(), po.routeVersion(),
                po.airspaceVersion(), po.conclusion(), encodeZoneIds(po.hitZoneIds()),
                po.routeKind() == RouteKind.LINE ? encodePoints(po.pointsSnapshot()) : null,
                po.routeKind() == RouteKind.CURVE ? encodeCurves(po.curveSnapshot()) : null,
                po.requestId(), po.operator(), po.createdAt());
    }

    /** 查询幂等去重记录，不存在返回 null。 */
    public DedupPo findDedup(String requestId) {
        return jdbc.query(
                "SELECT request_id, request_kind, operator, request_hash, response_json, created_at "
                        + "FROM request_dedup WHERE request_id = ?",
                (rs, n) -> new DedupPo(rs.getString("request_id"), rs.getString("request_kind"),
                        rs.getString("operator"), rs.getString("request_hash"),
                        rs.getString("response_json"), rs.getLong("created_at")),
                requestId).stream().findFirst().orElse(null);
    }

    /** 插入幂等去重记录（与业务变更在同一事务提交）。 */
    public void insertDedup(DedupPo po) {
        jdbc.update("INSERT INTO request_dedup "
                        + "(request_id, request_kind, operator, request_hash, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.requestId(), po.requestKind(), po.operator(), po.requestHash(),
                po.responseJson(), po.createdAt());
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

    /**
     * 曲线段完整控制点编码为 JSON：[[{x,y},{x,y},{x,y}], ...]，
     * 审核快照据此原样冻结 P0/P1/P2，不依赖调用方再次提供控制点。
     */
    public String encodeCurves(List<QuadraticCurve> segments) {
        try {
            return objectMapper.writeValueAsString(segments);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("曲线快照序列化失败", ex);
        }
    }

    /** 曲线段 JSON 解码；NULL 或空串返回空列表。 */
    public List<QuadraticCurve> decodeCurves(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(
                            ArrayList.class, QuadraticCurve.class));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("曲线快照反序列化失败", ex);
        }
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
