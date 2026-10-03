package com.example.starter.batch;

import java.sql.PreparedStatement;
import java.sql.Statement;
import com.example.starter.common.UtcTimes;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 批次持久化。提供行级锁查询，用于测温上传与放行之间的串行化。
 */
@Repository
public class BatchRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BatchRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 新建批次，状态为 FIRING，返回自增ID。
     */
    public long insert(String batchNo, long programVersionId, Long refireOfId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO kiln_batch (batch_no, program_version_id, status, refire_of_id, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setLong(2, programVersionId);
            ps.setString(3, BatchInfo.FIRING);
            if (refireOfId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, refireOfId);
            }
            ps.setObject(5, UtcTimes.toDb(clock.instant()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按ID查询批次。
     */
    public Optional<BatchInfo> findById(long id) {
        return queryOne("SELECT id, batch_no, program_version_id, status, refire_of_id, created_at"
                + " FROM kiln_batch WHERE id = ?", id);
    }

    /**
     * 按ID查询批次并加行级写锁，须在事务内调用。
     */
    public Optional<BatchInfo> findByIdForUpdate(long id) {
        return queryOne("SELECT id, batch_no, program_version_id, status, refire_of_id, created_at"
                + " FROM kiln_batch WHERE id = ? FOR UPDATE", id);
    }

    /**
     * 更新批次状态。
     */
    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE kiln_batch SET status = ? WHERE id = ?", status, id);
    }

    private Optional<BatchInfo> queryOne(String sql, long id) {
        List<BatchInfo> list = jdbc.query(sql, mapper(), id);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private RowMapper<BatchInfo> mapper() {
        return (rs, rowNum) -> {
            long refire = rs.getLong("refire_of_id");
            return new BatchInfo(rs.getLong("id"), rs.getString("batch_no"),
                    rs.getLong("program_version_id"), rs.getString("status"),
                    rs.wasNull() ? null : refire, UtcTimes.fromDb(rs.getObject("created_at", java.time.LocalDateTime.class)));
        };
    }
}