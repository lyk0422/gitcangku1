package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FreezeRestore;
import com.example.starter.firmware.domain.RestoreStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 冻结恢复申请与审批审计数据访问。每次申请独立成行，审批只更新该行状态，历史不可改。
 */
@Repository
public class RestoreRepository {

    private static final RowMapper<FreezeRestore> MAPPER = (rs, rowNum) -> new FreezeRestore(
            rs.getLong("id"), rs.getLong("freeze_id"), rs.getString("requested_by"),
            rs.getString("reason"), rs.getString("requested_at_utc"),
            RestoreStatus.valueOf(rs.getString("status")),
            rs.getString("reviewed_by"), rs.getString("reviewed_at_utc"));

    private static final String COLUMNS = "id, freeze_id, requested_by, reason, requested_at_utc, status,"
            + " reviewed_by, reviewed_at_utc";

    private final JdbcTemplate jdbc;

    public RestoreRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertPending(long freezeId, String requestedBy, String reason, String requestedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO version_freeze_restore"
                            + " (freeze_id, requested_by, reason, requested_at_utc, status)"
                            + " VALUES (?, ?, ?, ?, 'PENDING')",
                    new String[]{"id"});
            ps.setLong(1, freezeId);
            ps.setString(2, requestedBy);
            ps.setString(3, reason);
            ps.setString(4, requestedAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FreezeRestore> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze_restore WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FreezeRestore> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze_restore WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    public boolean existsPendingByFreeze(long freezeId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM version_freeze_restore WHERE freeze_id = ? AND status = 'PENDING'",
                Long.class, freezeId);
        return count != null && count > 0;
    }

    /**
     * 仅当仍为 PENDING 时置为 APPROVED 并写审批人、审批时刻，返回影响行数。
     */
    public int approve(long id, String reviewedBy, String reviewedAtUtc) {
        return jdbc.update("UPDATE version_freeze_restore SET status = 'APPROVED', reviewed_by = ?,"
                + " reviewed_at_utc = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'PENDING'",
                reviewedBy, reviewedAtUtc, id);
    }

    public List<FreezeRestore> findByFreeze(long freezeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze_restore WHERE freeze_id = ? ORDER BY id",
                MAPPER, freezeId);
    }
}
