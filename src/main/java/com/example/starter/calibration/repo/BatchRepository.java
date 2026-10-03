package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Batch;
import com.example.starter.calibration.model.BatchStatus;

/**
 * 测量批次持久化。batch_key 全局唯一作为幂等键；
 * 读数提交、复核、放行通过锁定批次行（FOR UPDATE）按事务提交顺序串行化。
 */
@Repository
public class BatchRepository {

    private static final RowMapper<Batch> MAPPER = (rs, rowNum) -> new Batch(
            rs.getLong("id"),
            rs.getString("batch_key"),
            rs.getString("instrument_id"),
            rs.getBigDecimal("target_value"),
            rs.getBigDecimal("tolerance"),
            rs.getInt("revision"),
            BatchStatus.valueOf(rs.getString("status")),
            rs.getString("released_by"),
            JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public BatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入批次并返回生成的 ID；batch_key 冲突时抛出 DuplicateKeyException。
     */
    public long insert(Batch batch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_batch "
                            + "(batch_key, instrument_id, target_value, tolerance, revision, status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batch.batchKey());
            ps.setString(2, batch.instrumentId());
            ps.setBigDecimal(3, batch.targetValue());
            ps.setBigDecimal(4, batch.tolerance());
            ps.setInt(5, batch.revision());
            ps.setString(6, batch.status().name());
            ps.setObject(7, JdbcTimes.toDb(batch.createdAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按批次键查询（不加锁）。
     */
    public Optional<Batch> findByKey(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ?", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询（不加锁）。
     */
    public Optional<Batch> findById(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 查询并加行锁（须在事务内调用），用于复核与放行的串行化。
     */
    public Optional<Batch> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按批次键查询并加行锁（须在事务内调用），用于读数提交、复核与放行的串行化。
     */
    public Optional<Batch> findByKeyForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM measurement_batch WHERE batch_key = ? FOR UPDATE", MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 已放行批次新增读数：修订版本递增并重新打开（须在持有行锁的事务内调用）。
     */
    public void reopenWithNewRevision(long id, int newRevision) {
        jdbc.update("UPDATE measurement_batch SET revision = ?, status = ?, "
                        + "released_by = NULL, released_at = NULL WHERE id = ?",
                newRevision, BatchStatus.OPEN.name(), id);
    }

    /**
     * 读数被 REJECT 排除后递增修订版本（须在持有行锁的事务内调用）。
     */
    public void bumpRevision(long id, int newRevision) {
        jdbc.update("UPDATE measurement_batch SET revision = ? WHERE id = ?", newRevision, id);
    }

    /**
     * 放行批次（须在持有行锁的事务内调用）。
     */
    public void markReleased(long id, String releasedBy, Instant releasedAt) {
        jdbc.update("UPDATE measurement_batch SET status = ?, released_by = ?, released_at = ? WHERE id = ?",
                BatchStatus.RELEASED.name(), releasedBy, JdbcTimes.toDb(releasedAt), id);
    }

    /**
     * 判断批次键是否已存在。
     */
    public boolean existsByKey(String batchKey) {
        try {
            jdbc.queryForObject("SELECT batch_key FROM measurement_batch WHERE batch_key = ?",
                    String.class, batchKey);
            return true;
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            return false;
        }
    }
}