package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 定时航线审查结果数据访问。
 * 坐标快照复用 "x,y;x,y" 文本格式，时刻快照为 UTC epoch 整秒逗号拼接，均不可变。
 */
@Repository
public class TimedReviewRepository {

    private final JdbcTemplate jdbc;

    public TimedReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = "review_id, route_id, route_version, airspace_version, "
            + "operator, conclusion, hit_zone_ids, points_snapshot, times_snapshot, "
            + "request_id, created_at";

    private static final RowMapper<TimedReviewPo> MAPPER = (rs, n) -> new TimedReviewPo(
            rs.getString("review_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getLong("airspace_version"),
            rs.getString("operator"),
            rs.getString("conclusion"),
            ReviewRepository.decodeZoneIds(rs.getString("hit_zone_ids")),
            ReviewRepository.decodePoints(rs.getString("points_snapshot")),
            decodeTimes(rs.getString("times_snapshot")),
            rs.getString("request_id"),
            rs.getLong("created_at"));

    /** 按 reviewId 查询不可变定时审查记录，不存在返回 null。 */
    public TimedReviewPo findReview(String reviewId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timed_review WHERE review_id = ?",
                MAPPER, reviewId).stream().findFirst().orElse(null);
    }

    /** 查询某航线最新的一条定时审查记录（按创建时间、reviewId 倒序），没有返回 null。 */
    public TimedReviewPo findLatestReview(String routeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM timed_review WHERE route_id = ? "
                        + "ORDER BY created_at DESC, review_id DESC LIMIT 1",
                MAPPER, routeId).stream().findFirst().orElse(null);
    }

    /** 插入不可变定时审查结果（调用方负责事务）。 */
    public void insertReview(TimedReviewPo po) {
        jdbc.update("INSERT INTO timed_review "
                        + "(review_id, route_id, route_version, airspace_version, operator, "
                        + "conclusion, hit_zone_ids, points_snapshot, times_snapshot, "
                        + "request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.reviewId(), po.routeId(), po.routeVersion(), po.airspaceVersion(),
                po.operator(), po.conclusion(),
                ReviewRepository.encodeZoneIds(po.hitZoneIds()),
                ReviewRepository.encodePoints(po.pointsSnapshot()),
                encodeTimes(po.arrivalTimesEpoch()),
                po.requestId(), po.createdAt());
    }

    /** 时刻列表编码：UTC epoch 整秒逗号拼接。 */
    public static String encodeTimes(List<Long> epochSeconds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < epochSeconds.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(epochSeconds.get(i));
        }
        return sb.toString();
    }

    /** 时刻列表解码，空串返回空列表。 */
    public static List<Long> decodeTimes(String text) {
        List<Long> times = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String part : text.split(",")) {
                times.add(Long.parseLong(part));
            }
        }
        return times;
    }
}