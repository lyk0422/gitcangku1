package com.example.starter.repo;

import com.example.starter.domain.PollinationOperation;
import com.example.starter.domain.PollinationOperation.OperationStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 授粉操作表数据访问。有效占位标记 effective_marker 的唯一约束保证同一花序至多一条有效操作。
 */
@Repository
public class PollinationOperationRepository {

    private static final RowMapper<PollinationOperation> MAPPER = (rs, rowNum) -> new PollinationOperation(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getLong("inflorescence_id"),
            rs.getLong("pollen_batch_id"),
            rs.getString("batch_no"),
            rs.getString("technician_id"),
            rs.getTimestamp("operated_at").toInstant(),
            OperationStatus.valueOf(rs.getString("status")),
            Optional.ofNullable(rs.getTimestamp("revoked_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("confirm_request_id"),
            rs.getString("confirm_request_hash"),
            Optional.ofNullable(rs.getTimestamp("confirmed_at")).map(Timestamp::toInstant).orElse(null),
            rs.getString("confirmed_batch_no"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PollinationOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建有效状态的授粉操作（effective_marker = inflorescenceId），返回完整记录。 */
    public PollinationOperation insert(String requestId, String requestHash, long inflorescenceId,
            long pollenBatchId, String batchNo, String technicianId, Instant operatedAt, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO pollination_operation (request_id, request_hash, inflorescence_id,"
                            + " pollen_batch_id, batch_no, technician_id, operated_at, status,"
                            + " effective_marker, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'EFFECTIVE', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, requestHash);
            ps.setLong(3, inflorescenceId);
            ps.setLong(4, pollenBatchId);
            ps.setString(5, batchNo);
            ps.setString(6, technicianId);
            ps.setTimestamp(7, Timestamp.from(operatedAt));
            ps.setLong(8, inflorescenceId);
            ps.setTimestamp(9, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return new PollinationOperation(keys.getKey().longValue(), requestId, requestHash, inflorescenceId,
                pollenBatchId, batchNo, technicianId, operatedAt, OperationStatus.EFFECTIVE,
                null, null, null, null, null, createdAt);
    }

    /** 按主键查询。 */
    public Optional<PollinationOperation> findById(long id) {
        return queryOne("SELECT * FROM pollination_operation WHERE id = ?", id);
    }

    /** 按授粉幂等请求键查询。 */
    public Optional<PollinationOperation> findByRequestId(String requestId) {
        return queryOne("SELECT * FROM pollination_operation WHERE request_id = ?", requestId);
    }

    /** 按花序查询全部操作（含历史），按 id 升序。 */
    public List<PollinationOperation> findByInflorescenceId(long inflorescenceId) {
        return jdbc.query("SELECT * FROM pollination_operation WHERE inflorescence_id = ? ORDER BY id",
                MAPPER, inflorescenceId);
    }

    /** 撤销：仅当状态为 EFFECTIVE 时置为 REVOKED 并释放占位标记，返回受影响行数。 */
    public int revokeIfEffective(long id, Instant revokedAt) {
        return jdbc.update(
                "UPDATE pollination_operation SET status = 'REVOKED', revoked_at = ?,"
                        + " effective_marker = NULL WHERE id = ? AND status = 'EFFECTIVE'",
                Timestamp.from(revokedAt), id);
    }

    /** 确认：仅当状态为 EFFECTIVE 且未确认时写入确认结果；确认失败会释放占位标记，返回受影响行数。 */
    public int confirmIfEffective(long id, OperationStatus result, String confirmRequestId,
            String confirmRequestHash, Instant confirmedAt, String confirmedBatchNo) {
        boolean success = result == OperationStatus.CONFIRMED_SUCCESS;
        return jdbc.update(
                "UPDATE pollination_operation SET status = ?, confirm_request_id = ?,"
                        + " confirm_request_hash = ?, confirmed_at = ?, confirmed_batch_no = ?,"
                        + " effective_marker = CASE WHEN ? THEN effective_marker ELSE NULL END"
                        + " WHERE id = ? AND status = 'EFFECTIVE' AND confirm_request_id IS NULL",
                result.name(), confirmRequestId, confirmRequestHash, Timestamp.from(confirmedAt),
                confirmedBatchNo, success, id);
    }

    private Optional<PollinationOperation> queryOne(String sql, Object parameter) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, MAPPER, parameter));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }
}