package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.domain.VersionFreeze;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 版本冻结区间数据访问。uk_freeze_active_version 唯一约束保证同一目标版本同时只有一条 FROZEN 冻结；
 * 恢复后 active_version 置 NULL，区间行保留。冻结与任务创建通过 release_order 行锁串行。
 */
@Repository
public class FreezeRepository {

    private static final RowMapper<VersionFreeze> MAPPER = (rs, rowNum) -> new VersionFreeze(
            rs.getLong("id"), rs.getString("version_key"), FreezeStatus.valueOf(rs.getString("status")),
            rs.getString("frozen_by"), rs.getString("frozen_reason"), rs.getString("frozen_at_utc"),
            rs.getString("approved_by"), rs.getString("restored_at_utc"));

    private static final String COLUMNS = "id, version_key, status, frozen_by, frozen_reason, frozen_at_utc,"
            + " approved_by, restored_at_utc";

    private final JdbcTemplate jdbc;

    public FreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String versionKey, String frozenBy, String frozenReason, String frozenAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO version_freeze"
                            + " (version_key, status, frozen_by, frozen_reason, frozen_at_utc, active_version)"
                            + " VALUES (?, 'FROZEN', ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, versionKey);
            ps.setString(2, frozenBy);
            ps.setString(3, frozenReason);
            ps.setString(4, frozenAtUtc);
            ps.setString(5, versionKey);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<VersionFreeze> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 按主键锁定冻结行（FOR UPDATE），与恢复申请/审批串行。
     */
    public Optional<VersionFreeze> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 查询某版本当前生效中的冻结（active_version 非空唯一）；只读，不在事务中加锁。
     */
    public Optional<VersionFreeze> findActiveByVersion(String versionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze WHERE active_version = ?",
                MAPPER, versionKey).stream().findFirst();
    }

    /**
     * 锁定所有以该版本为目标版本的未终结发布单行，使冻结与并发任务创建按提交顺序串行。
     */
    public void lockOpenReleasesByToVersion(String versionKey) {
        jdbc.queryForList("SELECT id FROM release_order WHERE to_version = ? AND active_model IS NOT NULL"
                + " FOR UPDATE", Long.class, versionKey);
    }

    /**
     * 仅当仍为 FROZEN 时置为 RESUMED 并释放 active_version，返回影响行数。
     */
    public int markResumed(long id, String approvedBy, String restoredAtUtc) {
        return jdbc.update("UPDATE version_freeze SET status = 'RESUMED', approved_by = ?,"
                + " restored_at_utc = ?, active_version = NULL, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'FROZEN'", approvedBy, restoredAtUtc, id);
    }

    public List<VersionFreeze> findByVersion(String versionKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM version_freeze WHERE version_key = ? ORDER BY id",
                MAPPER, versionKey);
    }
}
