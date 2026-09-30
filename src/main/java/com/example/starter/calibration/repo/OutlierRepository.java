package com.example.starter.calibration.repo;

import java.math.BigDecimal;
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

import com.example.starter.calibration.model.OutlierRecord;
import com.example.starter.calibration.model.OutlierReview;
import com.example.starter.calibration.model.OutlierStatus;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 离群记录与复核持久化。离群记录与复核均不可变：只提供插入与状态裁定更新，
 * 状态更新只允许在持有批次行锁的事务内进行。
 */
@Repository
public class OutlierRepository {

    private static final RowMapper<OutlierRecord> OUTLIER_MAPPER = (rs, rowNum) -> new OutlierRecord(
            rs.getLong("id"),
            rs.getString("outlier_key"),
            rs.getLong("batch_id"),
            rs.getInt("revision_no"),
            rs.getLong("measurement_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("computed_value"),
            rs.getBigDecimal("deviation"),
            rs.getBigDecimal("excess"),
            rs.getString("submitted_by"),
            OutlierStatus.valueOf(rs.getString("status")),
            JdbcTimes.fromDb(rs.getObject("detected_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("decided_at", LocalDateTime.class)));

    private static final RowMapper<OutlierReview> REVIEW_MAPPER = (rs, rowNum) -> new OutlierReview(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            rs.getString("reviewer"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("reason"),
            rs.getInt("measurement_revision"),
            rs.getLong("certificate_id"),
            rs.getBoolean("stale"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public OutlierRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入离群记录并返回 ID；同一读数同一修订重复检测受唯一约束保护。 */
    public long insert(OutlierRecord outlier) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_record "
                            + "(outlier_key, batch_id, revision_no, measurement_id, target_value, tolerance, "
                            + "lower_limit, upper_limit, raw_reading, computed_value, deviation, excess, "
                            + "submitted_by, status, detected_at, decided_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, outlier.outlierKey());
            ps.setLong(2, outlier.batchId());
            ps.setInt(3, outlier.revisionNo());
            ps.setLong(4, outlier.measurementId());
            ps.setBigDecimal(5, outlier.targetValue());
            ps.setBigDecimal(6, outlier.tolerance());
            ps.setBigDecimal(7, outlier.lowerLimit());
            ps.setBigDecimal(8, outlier.upperLimit());
            ps.setBigDecimal(9, outlier.rawReading());
            ps.setBigDecimal(10, outlier.computedValue());
            ps.setBigDecimal(11, outlier.deviation());
            ps.setBigDecimal(12, outlier.excess());
            ps.setString(13, outlier.submittedBy());
            ps.setString(14, outlier.status().name());
            ps.setObject(15, JdbcTimes.toDb(outlier.detectedAt()));
            ps.setObject(16, JdbcTimes.toDb(outlier.decidedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按业务键查询离群记录（不加锁）。 */
    public Optional<OutlierRecord> findByKey(String outlierKey) {
        return jdbc.query("SELECT * FROM outlier_record WHERE outlier_key = ?", OUTLIER_MAPPER, outlierKey)
                .stream().findFirst();
    }

    /** 按业务键查询离群记录并对行加锁（须在事务内）。 */
    public Optional<OutlierRecord> findByKeyForUpdate(String outlierKey) {
        return jdbc.query("SELECT * FROM outlier_record WHERE outlier_key = ? FOR UPDATE",
                OUTLIER_MAPPER, outlierKey).stream().findFirst();
    }

    /** 按 ID 查询（不加锁）。 */
    public Optional<OutlierRecord> findById(long id) {
        return jdbc.query("SELECT * FROM outlier_record WHERE id = ?", OUTLIER_MAPPER, id)
                .stream().findFirst();
    }

    /** 查询批次当前修订下的全部离群记录（按检测顺序 id 升序）。 */
    public List<OutlierRecord> findByBatchRevision(long batchId, int revisionNo) {
        return jdbc.query(
                "SELECT * FROM outlier_record WHERE batch_id = ? AND revision_no = ? ORDER BY id",
                OUTLIER_MAPPER, batchId, revisionNo);
    }

    /** 查询批次当前修订下指定状态的离群记录（PENDING 含待仲裁），按 id 升序。 */
    public List<OutlierRecord> findByBatchRevisionAndStatus(long batchId, int revisionNo, OutlierStatus status) {
        return jdbc.query(
                "SELECT * FROM outlier_record WHERE batch_id = ? AND revision_no = ? AND status = ? ORDER BY id",
                OUTLIER_MAPPER, batchId, revisionNo, status.name());
    }

    /** 统计批次当前修订下指定状态离群数量。 */
    public int countByBatchRevisionAndStatus(long batchId, int revisionNo, OutlierStatus status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE batch_id = ? AND revision_no = ? AND status = ?",
                Integer.class, batchId, revisionNo, status.name());
        return count == null ? 0 : count;
    }

    /** 写入有效裁决结论与裁决时间。 */
    public void markVerdict(long outlierId, OutlierStatus status, Instant decidedAt) {
        jdbc.update("UPDATE outlier_record SET status = ?, decided_at = ? WHERE id = ?",
                status.name(), JdbcTimes.toDb(decidedAt), outlierId);
    }

    /** 插入一条复核记录（同一复核人对同一离群仅允许一条，受唯一约束保护），返回 ID。 */
    public long insertReview(OutlierReview review) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_review "
                            + "(outlier_id, reviewer, decision, reason, measurement_revision, certificate_id, "
                            + "stale, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, review.outlierId());
            ps.setString(2, review.reviewer());
            ps.setString(3, review.decision().name());
            ps.setString(4, review.reason());
            ps.setInt(5, review.measurementRevision());
            ps.setLong(6, review.certificateId());
            ps.setBoolean(7, review.stale());
            ps.setObject(8, JdbcTimes.toDb(review.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 将一条复核标记为 STALE（引用的测量修订或证书版本失效）。 */
    public void markReviewStale(long reviewId) {
        jdbc.update("UPDATE outlier_review SET stale = TRUE WHERE id = ?", reviewId);
    }

    /** 查询离群记录的全部复核，按提交顺序 id 升序。 */
    public List<OutlierReview> findReviews(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? ORDER BY id",
                REVIEW_MAPPER, outlierId);
    }

    /** 查询离群记录的全部复核并对离群行持锁前提下读取（用于裁决的一致集合）。 */
    public List<OutlierReview> findReviewsForUpdate(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_review WHERE outlier_id = ? ORDER BY id FOR UPDATE",
                REVIEW_MAPPER, outlierId);
    }

    /** 查询批次当前修订下全部复核（诊断用），按 id 升序。 */
    public List<OutlierReview> findReviewsByBatchRevision(long batchId, int revisionNo) {
        return jdbc.query(
                "SELECT * FROM outlier_review r JOIN outlier_record o ON o.id = r.outlier_id "
                        + "WHERE o.batch_id = ? AND o.revision_no = ? ORDER BY r.id",
                REVIEW_MAPPER, batchId, revisionNo);
    }

    /** 判断某读数在指定修订是否已存在离群记录。 */
    public boolean existsForMeasurement(long measurementId, int revisionNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outlier_record WHERE measurement_id = ? AND revision_no = ?",
                Integer.class, measurementId, revisionNo);
        return count != null && count > 0;
    }

    /** 读取计算值（测试与诊断辅助）。 */
    public Optional<BigDecimal> findExcessById(long id) {
        return findById(id).map(OutlierRecord::excess);
    }
}
