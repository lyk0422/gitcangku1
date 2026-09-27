package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.WeatherViolation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 审核结果与幂等去重数据访问。
 * 点快照以 "x,y;x,y" 文本保存，命中 zoneId 以逗号拼接；
 * 气象不达标明细与气象快照以 JSON 保存，均为不可变内容。
 */
@Repository
public class ReviewRepository {

    private static final String REVIEW_COLUMNS =
            "review_id, route_id, route_version, airspace_version, weather_version, "
                    + "forecast_version, duty_version, conclusion, hit_zone_ids, "
                    + "weather_violations, weather_snapshot, points_snapshot, request_id, created_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<ReviewPo> reviewMapper = (rs, n) -> new ReviewPo(
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getLong("weather_version"),
            (Long) rs.getObject("forecast_version"),
            rs.getLong("duty_version"),
            rs.getString("conclusion"),
            decodeZoneIds(rs.getString("hit_zone_ids")),
            decodeViolations(rs.getString("weather_violations")),
            rs.getString("weather_snapshot"),
            decodePoints(rs.getString("points_snapshot")),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 按 reviewId 查询不可变审核记录，不存在返回 null。 */
    public ReviewPo findReview(String reviewId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE review_id = ?",
                reviewMapper, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条审核记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public ReviewPo findLatestReview(String routeId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                reviewMapper, routeId).stream().findFirst().orElse(null);
    }

    /** 查询某航线的全部审核历史（按创建时间、reviewId 升序，稳定排序）。 */
    public List<ReviewPo> findReviewsByRoute(String routeId) {
        return jdbc.query(
                "SELECT " + REVIEW_COLUMNS + " FROM review WHERE route_id = ? "
                        + "ORDER BY created_at, review_id",
                reviewMapper, routeId);
    }

    /** 插入不可变审核结果（调用方负责事务）。 */
    public void insertReview(ReviewPo po) {
        jdbc.update("INSERT INTO review (" + REVIEW_COLUMNS + ") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.weatherVersion(), po.forecastVersion(), po.dutyVersion(),
                po.conclusion(), encodeZoneIds(po.hitZoneIds()),
                encodeViolations(po.weatherViolations()), po.weatherSnapshot(),
                encodePoints(po.pointsSnapshot()), po.requestId(), po.createdAt());
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

    /** 气象不达标明细编码为 JSON 数组（集合经 JSON 转义，不做分隔符拼接）。 */
    private String encodeViolations(List<WeatherViolation> violations) {
        try {
            return objectMapper.writeValueAsString(violations);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("气象不达标明细序列化失败", ex);
        }
    }

    /** 气象不达标明细 JSON 解码；空串/null 返回空列表。 */
    private List<WeatherViolation> decodeViolations(String json) {
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<WeatherViolation>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("气象不达标明细反序列化失败", ex);
        }
    }
}
