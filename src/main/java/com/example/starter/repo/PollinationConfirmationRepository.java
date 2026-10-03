package com.example.starter.repo;

import com.example.starter.domain.ConfirmationResult;
import com.example.starter.domain.PollinationConfirmation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 授粉结果确认仓储。
 */
@Repository
public class PollinationConfirmationRepository {

    private final JdbcTemplate jdbc;

    public PollinationConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建确认记录；request_id 或 operation_id 重复时抛出 DuplicateKeyException。 */
    public long insert(String requestId, String requestHash, long operationId, ConfirmationResult result,
                       String technician, Instant confirmedAt, Instant createdAt) {
        jdbc.update("INSERT INTO pollination_confirmation (request_id, request_hash, operation_id,"
                        + " result, technician, confirmed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, requestHash, operationId, result.name(), technician,
                UtcTimes.toDb(confirmedAt), UtcTimes.toDb(createdAt));
        Long id = jdbc.queryForObject("SELECT id FROM pollination_confirmation WHERE request_id = ?", Long.class, requestId);
        return id == null ? -1L : id;
    }

    /** 按幂等请求键查询。 */
    public Optional<PollinationConfirmation> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM pollination_confirmation WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /** 按操作 ID 查询（一个操作至多一条确认）。 */
    public Optional<PollinationConfirmation> findByOperationId(long operationId) {
        return jdbc.query("SELECT * FROM pollination_confirmation WHERE operation_id = ?", this::map, operationId)
                .stream().findFirst();
    }

    /** 按操作 ID 集合批量查询，用于时间线组装。 */
    public List<PollinationConfirmation> findByOperationIds(List<Long> operationIds) {
        if (operationIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", operationIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM pollination_confirmation WHERE operation_id IN (" + placeholders + ")",
                this::map, operationIds.toArray());
    }

    private PollinationConfirmation map(ResultSet rs, int rowNum) throws SQLException {
        return new PollinationConfirmation(rs.getLong("id"), rs.getString("request_id"),
                rs.getString("request_hash"), rs.getLong("operation_id"),
                ConfirmationResult.valueOf(rs.getString("result")), rs.getString("technician"),
                UtcTimes.fromDb(rs.getObject("confirmed_at", LocalDateTime.class)),
                UtcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));
    }
}