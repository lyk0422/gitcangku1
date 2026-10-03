package com.example.starter.repo;

import com.example.starter.domain.OperationStatus;
import com.example.starter.domain.PollinationOperation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 授粉操作记录仓储。
 */
@Repository
public class PollinationOperationRepository {

    private final JdbcTemplate jdbc;

    public PollinationOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建有效操作；request_id 重复时抛出 DuplicateKeyException。 */
    public long insert(String requestId, String requestHash, long inflorescenceId, String pollenBatchNo,
                       String technician, Instant executedAt, Instant createdAt) {
        jdbc.update("INSERT INTO pollination_operation (request_id, request_hash, inflorescence_id,"
                        + " pollen_batch_no, technician, executed_at, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, requestHash, inflorescenceId, pollenBatchNo, technician,
                UtcTimes.toDb(executedAt), OperationStatus.EFFECTIVE.name(), UtcTimes.toDb(createdAt));
        Long id = jdbc.queryForObject("SELECT id FROM pollination_operation WHERE request_id = ?", Long.class, requestId);
        return id == null ? -1L : id;
    }

    /** 按 ID 查询。 */
    public Optional<PollinationOperation> findById(long id) {
        return jdbc.query("SELECT * FROM pollination_operation WHERE id = ?", this::map, id).stream().findFirst();
    }

    /** 按幂等请求键查询。 */
    public Optional<PollinationOperation> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM pollination_operation WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /** 按花序查询全部操作，按执行时刻升序。 */
    public List<PollinationOperation> findByInflorescenceId(long inflorescenceId) {
        return jdbc.query("SELECT * FROM pollination_operation WHERE inflorescence_id = ? ORDER BY executed_at, id",
                this::map, inflorescenceId);
    }

    /** 撤销：仅当仍处于 EFFECTIVE 时生效；返回 0 表示已被确认或已撤销（终态竞争失败）。 */
    public int revokeIfEffective(long id, Instant revokedAt) {
        return jdbc.update("UPDATE pollination_operation SET status = ?, revoked_at = ? WHERE id = ? AND status = ?",
                OperationStatus.REVOKED.name(), UtcTimes.toDb(revokedAt), id, OperationStatus.EFFECTIVE.name());
    }

    /** 标记已确认：仅当仍处于 EFFECTIVE 时生效；返回 0 表示已被撤销（终态竞争失败）。 */
    public int confirmIfEffective(long id) {
        return jdbc.update("UPDATE pollination_operation SET status = ? WHERE id = ? AND status = ?",
                OperationStatus.CONFIRMED.name(), id, OperationStatus.EFFECTIVE.name());
    }

    private PollinationOperation map(ResultSet rs, int rowNum) throws SQLException {
        return new PollinationOperation(rs.getLong("id"), rs.getString("request_id"),
                rs.getString("request_hash"), rs.getLong("inflorescence_id"),
                rs.getString("pollen_batch_no"), rs.getString("technician"),
                UtcTimes.fromDb(rs.getObject("executed_at", LocalDateTime.class)),
                OperationStatus.valueOf(rs.getString("status")),
                UtcTimes.fromDb(rs.getObject("revoked_at", LocalDateTime.class)),
                UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));
    }
}