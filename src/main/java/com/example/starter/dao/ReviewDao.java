package com.example.starter.dao;

import com.example.starter.domain.Conclusion;
import com.example.starter.domain.ReviewRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 审核结果表访问：记录不可变，仅插入与查询。
 */
@Repository
public class ReviewDao {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ReviewDao(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入审核结果。
     */
    public void insert(ReviewRecord review, String requestId) {
        jdbc.update("INSERT INTO reviews (review_id, route_id, route_version, airspace_version,"
                        + " conclusion, hit_zone_ids, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                review.reviewId(), review.routeId(), review.routeVersion(), review.airspaceVersion(),
                review.conclusion().name(), writeHitZoneIds(review.hitZoneIds()), requestId,
                Timestamp.from(review.createdAt()));
    }

    /**
     * 按主键查询审核结果（历史查询保留原结论）。
     */
    public Optional<ReviewRecord> findById(String reviewId) {
        return jdbc.query("SELECT * FROM reviews WHERE review_id = ?", this::map, reviewId)
                .stream().findFirst();
    }

    /**
     * 查询航线最近一次审核结果。
     */
    public Optional<ReviewRecord> findLatestByRoute(String routeId) {
        return jdbc.query("SELECT * FROM reviews WHERE route_id = ?"
                        + " ORDER BY created_at DESC, review_id DESC LIMIT 1", this::map, routeId)
                .stream().findFirst();
    }

    private ReviewRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ReviewRecord(
                rs.getString("review_id"),
                rs.getString("route_id"),
                rs.getInt("route_version"),
                rs.getLong("airspace_version"),
                Conclusion.valueOf(rs.getString("conclusion")),
                readHitZoneIds(rs.getString("hit_zone_ids")),
                rs.getTimestamp("created_at").toInstant());
    }

    private String writeHitZoneIds(List<String> hitZoneIds) {
        try {
            return objectMapper.writeValueAsString(hitZoneIds);
        } catch (Exception e) {
            throw new IllegalStateException("序列化命中区域失败", e);
        }
    }

    private List<String> readHitZoneIds(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化命中区域失败", e);
        }
    }
}
