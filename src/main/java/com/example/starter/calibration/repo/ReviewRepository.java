package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchReview;
import com.example.starter.calibration.model.ReviewItem;

/**
 * 放行后复核持久化。每批次至多一条成功复核（uk_review_batch），reviewKey 全局唯一（uk_review_key）。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<BatchReview> REVIEW_MAPPER = (rs, rowNum) -> new BatchReview(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getString("review_key"),
            rs.getString("reviewer"),
            rs.getString("snapshot"),
            JdbcTimes.fromDb(rs.getObject("reviewed_at", LocalDateTime.class)));

    private static final RowMapper<ReviewItem> ITEM_MAPPER = (rs, rowNum) -> new ReviewItem(
            rs.getLong("id"),
            rs.getLong("review_id"),
            rs.getInt("position"),
            rs.getLong("measurement_id"),
            rs.getInt("version"),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复核主记录并返回自增 ID。
     */
    public long insertReview(BatchReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO batch_review (batch_id, review_key, reviewer, snapshot, reviewed_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, review.batchId());
            ps.setString(2, review.reviewKey());
            ps.setString(3, review.reviewer());
            ps.setString(4, review.snapshot());
            ps.setObject(5, JdbcTimes.toDb(review.reviewedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 插入一条驳回明细。
     */
    public void insertItem(ReviewItem item) {
        jdbc.update("INSERT INTO review_item (review_id, position, measurement_id, version, reason) "
                        + "VALUES (?, ?, ?, ?, ?)",
                item.reviewId(), item.position(), item.measurementId(), item.version(), item.reason());
    }

    /**
     * 按批次 ID 查询复核记录（不加锁）。
     */
    public Optional<BatchReview> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM batch_review WHERE batch_id = ?", REVIEW_MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 按复核幂等键查询复核记录。
     */
    public Optional<BatchReview> findByReviewKey(String reviewKey) {
        return jdbc.query("SELECT * FROM batch_review WHERE review_key = ?", REVIEW_MAPPER, reviewKey)
                .stream().findFirst();
    }

    /**
     * 查询某复核记录的全部驳回明细，按位置升序。
     */
    public List<ReviewItem> findItems(long reviewId) {
        return jdbc.query("SELECT * FROM review_item WHERE review_id = ? ORDER BY position",
                ITEM_MAPPER, reviewId);
    }
}
