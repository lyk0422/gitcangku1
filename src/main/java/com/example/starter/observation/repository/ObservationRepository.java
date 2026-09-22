package com.example.starter.observation.repository;

import com.example.starter.observation.model.DedupRecord;
import com.example.starter.observation.model.ObservationSnapshot;
import com.example.starter.observation.model.OperationType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 观测版本快照与写操作幂等记录的数据访问层。
 */
@Repository
public class ObservationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<ObservationSnapshot> SNAPSHOT_MAPPER = (rs, rowNum) -> {
        ObservationSnapshot snapshot = new ObservationSnapshot();
        snapshot.setId(rs.getLong("id"));
        snapshot.setObservationId(rs.getString("observation_id"));
        snapshot.setVersion(rs.getInt("version"));
        snapshot.setLocation(rs.getString("location"));
        snapshot.setReading(rs.getString("reading"));
        snapshot.setRemark(rs.getString("remark"));
        snapshot.setDeleted(rs.getInt("deleted") == 1);
        Timestamp createdAt = rs.getTimestamp("created_at");
        snapshot.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return snapshot;
    };

    private static final RowMapper<DedupRecord> DEDUP_MAPPER = (rs, rowNum) -> {
        DedupRecord record = new DedupRecord();
        record.setRequestId(rs.getString("request_id"));
        record.setOperation(OperationType.valueOf(rs.getString("operation")));
        record.setObservationId(rs.getString("observation_id"));
        record.setRequestHash(rs.getString("request_hash"));
        record.setStatus(rs.getString("status"));
        int httpStatus = rs.getInt("http_status");
        record.setHttpStatus(rs.wasNull() ? null : httpStatus);
        record.setResponseBody(rs.getString("response_body"));
        record.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        record.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return record;
    };

    /**
     * 插入完整版本快照（墓碑版本的业务字段为 null）。
     */
    public void insertVersion(ObservationSnapshot snapshot) {
        jdbcTemplate.update(
                "INSERT INTO observation_version "
                        + "(observation_id, version, location, reading, remark, deleted, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.getObservationId(),
                snapshot.getVersion(),
                snapshot.getLocation(),
                snapshot.getReading(),
                snapshot.getRemark(),
                snapshot.isDeleted() ? 1 : 0,
                Timestamp.from(snapshot.getCreatedAt()));
    }

    /**
     * 读取当前（版本号最大）快照，不含历史行锁。
     */
    public Optional<ObservationSnapshot> findLatest(String observationId) {
        List<ObservationSnapshot> list = jdbcTemplate.query(
                "SELECT * FROM observation_version WHERE observation_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                SNAPSHOT_MAPPER, observationId);
        return list.stream().findFirst();
    }

    /**
     * 在写事务内锁定该观测的全部版本行，返回按版本倒序排列的快照，串行化同一观测的并发写；
     * 调用方取首行即最新版本。
     */
    public List<ObservationSnapshot> findAllForUpdate(String observationId) {
        return jdbcTemplate.query(
                "SELECT * FROM observation_version WHERE observation_id = ? ORDER BY version DESC FOR UPDATE",
                SNAPSHOT_MAPPER, observationId);
    }

    /**
     * 读取指定历史版本；墓碑版本同样可查，但不带业务字段。
     */
    public Optional<ObservationSnapshot> findVersion(String observationId, int version) {
        List<ObservationSnapshot> list = jdbcTemplate.query(
                "SELECT * FROM observation_version WHERE observation_id = ? AND version = ?",
                SNAPSHOT_MAPPER, observationId, version);
        return list.stream().findFirst();
    }

    /**
     * 抢占幂等键并写入 PENDING 记录；键已存在时返回 false，由调用方判定重放或异参冲突。
     */
    public boolean insertDedupPending(DedupRecord record) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO dedup_record "
                            + "(request_id, operation, observation_id, request_hash, status, "
                            + "http_status, response_body, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDING', NULL, NULL, ?, ?)",
                    record.getRequestId(),
                    record.getOperation().name(),
                    record.getObservationId(),
                    record.getRequestHash(),
                    Timestamp.from(record.getCreatedAt()),
                    Timestamp.from(record.getUpdatedAt()));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    /**
     * 将幂等记录置为 DONE 并保存原成功结果，与业务变更在同一事务内原子提交。
     */
    public void completeDedup(String requestId, int httpStatus, String responseBody, Instant now) {
        jdbcTemplate.update(
                "UPDATE dedup_record SET status = 'DONE', http_status = ?, response_body = ?, "
                        + "updated_at = ? WHERE request_id = ?",
                httpStatus, responseBody, Timestamp.from(now), requestId);
    }

    /**
     * 业务失败时删除 PENDING 占位，失败不占用幂等键。
     */
    public void deleteDedup(String requestId) {
        jdbcTemplate.update("DELETE FROM dedup_record WHERE request_id = ?", requestId);
    }

    public Optional<DedupRecord> findDedup(String requestId) {
        List<DedupRecord> list = jdbcTemplate.query(
                "SELECT * FROM dedup_record WHERE request_id = ?",
                DEDUP_MAPPER, requestId);
        return list.stream().findFirst();
    }
}
