package com.example.starter.repo;

import com.example.starter.domain.TimedPoint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 定时航线可行性审查结果数据访问。
 * 航点快照以 JSON 数组文本保存（坐标为最多 3 位小数的字符串，时刻为 UTC 整秒 epoch），
 * 结构化存储避免分隔符碰撞并保留小数精度。
 */
@Repository
public class TimedReviewRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TimedReviewRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<TimedReviewPo> mapper = (rs, n) -> new TimedReviewPo(
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("operator"),
            rs.getString("conclusion"),
            ReviewRepository.decodeZoneIds(rs.getString("hit_zone_ids")),
            decodePoints(rs.getString("points_snapshot")),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    private static final String COLUMNS = "review_id, route_id, route_version, airspace_version, "
            + "operator, conclusion, hit_zone_ids, points_snapshot, request_id, created_at";

    /** 按 reviewId 查询不可变定时审查记录，不存在返回 null。 */
    public TimedReviewPo findReview(String reviewId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timed_review WHERE review_id = ?",
                mapper, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条定时审查记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public TimedReviewPo findLatestReview(String routeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timed_review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                mapper, routeId).stream().findFirst().orElse(null);
    }

    /** 插入不可变定时审查结果（调用方负责事务）。 */
    public void insertReview(TimedReviewPo po) {
        jdbc.update("INSERT INTO timed_review "
                        + "(review_id, route_id, route_version, airspace_version, operator, conclusion, "
                        + "hit_zone_ids, points_snapshot, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(), po.operator(),
                po.conclusion(), ReviewRepository.encodeZoneIds(po.hitZoneIds()),
                encodePoints(po.pointsSnapshot()), po.requestId(), po.createdAt());
    }

    /** 航点快照编码为 JSON 数组：[{"x":"100.001","y":"0","t":1759555200}, ...]。 */
    private String encodePoints(List<TimedPoint> points) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (TimedPoint p : points) {
            ObjectNode node = array.addObject();
            node.put("x", p.x().toPlainString());
            node.put("y", p.y().toPlainString());
            node.put("t", p.arrivalEpochSecond());
        }
        try {
            return objectMapper.writeValueAsString(array);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("航点快照序列化失败", ex);
        }
    }

    /** 航点快照解码。 */
    private List<TimedPoint> decodePoints(String json) {
        try {
            List<TimedPoint> points = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(json)) {
                points.add(new TimedPoint(
                        new BigDecimal(node.get("x").asText()),
                        new BigDecimal(node.get("y").asText()),
                        node.get("t").asLong()));
            }
            return points;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("航点快照反序列化失败", ex);
        }
    }
}