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

import com.example.starter.calibration.model.BatchEvent;
import com.example.starter.calibration.model.BatchStatus;
import com.example.starter.calibration.model.MeasurementBatch;
import com.example.starter.calibration.model.MeasurementRevision;

/**
 * 测量批次、修订版本与不可变事件流水的持久化。
 * 批次行锁串行化同一批次上的提交、复核与放行；修订与事件只追加，不提供更新/删除。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<MeasurementBatch> BATCH_MAPPER = (rs, rowNum) -> new MeasurementBatch(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("instrument_id"),
            rs.getInt("current_revision_no"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private static final RowMapper<MeasurementRevision> REVISION_MAPPER = (rs, rowNum) ->
            new MeasurementRevision(
                    rs.getLong("id"),
                    rs.getLong("batch_id"),
                    rs.getInt("revision_no"),
                    rs.getBigDecimal("target_value"),
                    rs.getBigDecimal("tolerance"),
                    rs.getString("reason"),
                    rs.getString("created_by"),
                    JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                    JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)));

    private static final RowMapper<BatchEvent> EVENT_MAPPER = (rs, rowNum) -> new BatchEvent(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("revision_no"),
            rs.getString("event_type"),
            rs.getString("actor"),
            rs.getString("payload"),
            JdbcTimes.fromDb(rs.getObject("occurred_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入批次并返回 ID；batch_key 冲突抛出 DuplicateKeyException。
     */
    public long insertBatch(String batchKey, String instrumentId, String createdBy, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_batch "
                            + "(batch_key, instrument_id, current_revision_no, status, created_by, created_at) "
                            + "VALUES (?, ?, 1, 'OPEN', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchKey);
            ps.setString(2, instrumentId);
            ps.setString(3, createdBy);
            ps.setObject(4, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按业务键查询批次（不加锁）。 */
    public Optional<MeasurementBatch> findByKey(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ?", BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    /** 按业务键查询批次并对批次行加锁（须在事务内），串行化同批次的全部裁决操作。 */
    public Optional<MeasurementBatch> findByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ? FOR UPDATE",
                BATCH_MAPPER, batchKey).stream().findFirst();
    }

    /** 按 ID 查询批次（不加锁）。 */
    public Optional<MeasurementBatch> findById(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ?", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    /** 按 ID 查询批次并加行锁。 */
    public Optional<MeasurementBatch> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ? FOR UPDATE", BATCH_MAPPER, id)
                .stream().findFirst();
    }

    /** 推进当前修订号并把批次置回 OPEN（后续修订场景）。 */
    public void advanceRevision(long batchId, int newRevisionNo) {
        jdbc.update("UPDATE measurement_batch SET current_revision_no = ?, status = 'OPEN' WHERE id = ?",
                newRevisionNo, batchId);
    }

    /** 当前修订放行成功后更新批次状态。 */
    public void markReleased(long batchId) {
        jdbc.update("UPDATE measurement_batch SET status = 'RELEASED' WHERE id = ?", batchId);
    }

    /** 插入首版或后续修订，返回修订记录 ID。 */
    public long insertRevision(long batchId, int revisionNo, BigDecimal targetValue, BigDecimal tolerance,
                               String reason, String createdBy, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_revision "
                            + "(batch_id, revision_no, target_value, tolerance, reason, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, revisionNo);
            ps.setBigDecimal(3, targetValue);
            ps.setBigDecimal(4, tolerance);
            ps.setString(5, reason);
            ps.setString(6, createdBy);
            ps.setObject(7, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按 ID 查询修订（不加锁）。 */
    public Optional<MeasurementRevision> findRevision(long revisionId) {
        return jdbc.query("SELECT * FROM measurement_revision WHERE id = ?", REVISION_MAPPER, revisionId)
                .stream().findFirst();
    }

    /** 按批次与版本号查询修订（不加锁）。 */
    public Optional<MeasurementRevision> findRevisionByNo(long batchId, int revisionNo) {
        return jdbc.query("SELECT * FROM measurement_revision WHERE batch_id = ? AND revision_no = ?",
                REVISION_MAPPER, batchId, revisionNo).stream().findFirst();
    }

    /** 按批次与版本号查询修订并加行锁，冻结放行/修订判定的配置快照。 */
    public Optional<MeasurementRevision> findRevisionByNoForUpdate(long batchId, int revisionNo) {
        return jdbc.query(
                "SELECT * FROM measurement_revision WHERE batch_id = ? AND revision_no = ? FOR UPDATE",
                REVISION_MAPPER, batchId, revisionNo).stream().findFirst();
    }

    /** 查询批次全部修订，按版本号升序。 */
    public List<MeasurementRevision> findRevisions(long batchId) {
        return jdbc.query("SELECT * FROM measurement_revision WHERE batch_id = ? ORDER BY revision_no",
                REVISION_MAPPER, batchId);
    }

    /** 标记修订已放行（须在持有批次/修订锁的事务内调用）。 */
    public void markRevisionReleased(long batchId, int revisionNo, Instant releasedAt) {
        jdbc.update("UPDATE measurement_revision SET released_at = ? WHERE batch_id = ? AND revision_no = ?",
                JdbcTimes.toDb(releasedAt), batchId, revisionNo);
    }

    /**
     * 追加一条不可变事件。
     */
    public void appendEvent(long batchId, int revisionNo, String eventType, String actor,
                            String payload, Instant occurredAt) {
        jdbc.update("INSERT INTO batch_event (batch_id, revision_no, event_type, actor, payload, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                batchId, revisionNo, eventType, actor, payload, JdbcTimes.toDb(occurredAt));
    }

    /** 查询批次全部事件，按 id 升序（即提交顺序）。 */
    public List<BatchEvent> findEvents(long batchId) {
        return jdbc.query("SELECT * FROM batch_event WHERE batch_id = ? ORDER BY id", EVENT_MAPPER, batchId);
    }

    /** 查询批次指定修订的事件，按 id 升序。 */
    public List<BatchEvent> findEventsByRevision(long batchId, int revisionNo) {
        return jdbc.query("SELECT * FROM batch_event WHERE batch_id = ? AND revision_no = ? ORDER BY id",
                EVENT_MAPPER, batchId, revisionNo);
    }
}
