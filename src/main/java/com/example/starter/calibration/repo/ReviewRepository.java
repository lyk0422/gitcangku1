package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BatchSnapshotItem;
import com.example.starter.calibration.model.ReviewRecord;

/**
 * 复核记录与批次版本快照持久化。review_key 唯一作为复核幂等键；快照只增不改。
 */
@Repository
public class ReviewRepository {

    private static final RowMapper<ReviewRecord> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRecord(
            rs.getLong("id"),
            rs.getString("review_key"),
            rs.getString("batch_id"),
            rs.getString("reviewer"),
            rs.getString("request_fingerprint"),
            JdbcTimes.fromDb(rs.getObject("reviewed_at", LocalDateTime.class)));

    private static final RowMapper<BatchSnapshotItem> SNAPSHOT_MAPPER = (rs, rowNum) -> new BatchSnapshotItem(
            rs.getLong("id"),
            rs.getString("batch_id"),
            rs.getLong("review_id"),
            rs.getLong("measurement_id"),
            rs.getInt("measurement_version"),
            rs.getBoolean("rejected"),
            rs.getString("reason"));

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入复核记录并返回生成的 ID；review_key 冲突时抛出 DuplicateKeyException。
     */
    public long insertReview(String reviewKey, String batchId, String reviewer,
                             String fingerprint, Instant reviewedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO review_record "
                            + "(review_key, batch_id, reviewer, request_fingerprint, reviewed_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reviewKey);
            ps.setString(2, batchId);
            ps.setString(3, reviewer);
            ps.setString(4, fingerprint);
            ps.setObject(5, JdbcTimes.toDb(reviewedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按复核幂等键查询（不加锁）。
     */
    public Optional<ReviewRecord> findByReviewKey(String reviewKey) {
        return jdbc.query("SELECT * FROM review_record WHERE review_key = ?", REVIEW_MAPPER, reviewKey)
                .stream().findFirst();
    }

    /**
     * 查询某批次的复核记录（每批至多一次成功复核）。
     */
    public Optional<ReviewRecord> findByBatchId(String batchId) {
        return jdbc.query("SELECT * FROM review_record WHERE batch_id = ?", REVIEW_MAPPER, batchId)
                .stream().findFirst();
    }

    /**
     * 追加一条批次版本快照。
     */
    public void insertSnapshot(String batchId, long reviewId, long measurementId,
                               int measurementVersion, boolean rejected, String reason) {
        jdbc.update("INSERT INTO batch_snapshot "
                        + "(batch_id, review_id, measurement_id, measurement_version, rejected, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                batchId, reviewId, measurementId, measurementVersion, rejected, reason);
    }

    /**
     * 查询某批次冻结的版本快照（按写入顺序）。
     */
    public List<BatchSnapshotItem> findSnapshot(String batchId) {
        return jdbc.query("SELECT * FROM batch_snapshot WHERE batch_id = ? ORDER BY id",
                SNAPSHOT_MAPPER, batchId);
    }
}
