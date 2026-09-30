package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FreezeRecord;
import com.example.starter.firmware.domain.FreezeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 版本冻结记录数据访问。active_version 唯一约束保证每版本至多一条 FROZEN 记录；
 * 冻结、申请恢复、审批恢复共用行锁（SELECT ... FOR UPDATE）形成一致提交顺序。
 */
@Repository
public class FreezeRecordRepository {

    private static final RowMapper<FreezeRecord> MAPPER = (rs, rowNum) -> new FreezeRecord(
            rs.getLong("id"), rs.getString("version"), FreezeStatus.valueOf(rs.getString("status")),
            rs.getString("created_by"), rs.getString("reason"), rs.getString("frozen_at_utc"),
            rs.getString("resume_requested_by"), rs.getString("resume_reason"),
            rs.getString("resume_requested_at_utc"), rs.getString("approved_by"),
            rs.getString("resumed_at_utc"));

    private static final String COLUMNS = "id, version, status, created_by, reason, frozen_at_utc,"
            + " resume_requested_by, resume_reason, resume_requested_at_utc, approved_by, resumed_at_utc";

    private final JdbcTemplate jdbc;

    public FreezeRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条 FROZEN 记录；同版本已有生效冻结时由 uk_freeze_active_version 兜底冲突。
     */
    public long insert(String version, String createdBy, String reason, String frozenAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_freeze_record (version, status, created_by, reason, frozen_at_utc,"
                            + " active_version) VALUES (?, 'FROZEN', ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, version);
            ps.setString(2, createdBy);
            ps.setString(3, reason);
            ps.setString(4, frozenAtUtc);
            ps.setString(5, version);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FreezeRecord> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_record WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FreezeRecord> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_record WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询版本当前生效（FROZEN）冻结，无任何锁；用于只读查询。
     */
    public Optional<FreezeRecord> findActiveByVersion(String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_record WHERE active_version = ?",
                MAPPER, version).stream().findFirst();
    }

    /**
     * 在行锁内查询版本当前生效冻结；冻结/申请/审批与任务拉取靠此锁按提交顺序决胜。
     */
    public Optional<FreezeRecord> findActiveByVersionForUpdate(String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_record WHERE active_version = ?"
                + " FOR UPDATE", MAPPER, version).stream().findFirst();
    }

    public List<FreezeRecord> findByVersion(String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_record WHERE version = ? ORDER BY id",
                MAPPER, version);
    }

    /**
     * 登记恢复申请；仅当记录仍为 FROZEN 且尚无申请时生效，返回影响行数。
     */
    public int markResumeRequested(long id, String operator, String reason, String requestedAtUtc) {
        return jdbc.update("UPDATE release_freeze_record SET resume_requested_by = ?, resume_reason = ?,"
                + " resume_requested_at_utc = ?, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'FROZEN' AND resume_requested_at_utc IS NULL",
                operator, reason, requestedAtUtc, id);
    }

    /**
     * 审批恢复：仅当记录仍为 FROZEN 且已有恢复申请时生效，清空 active_version 释放唯一键，返回影响行数。
     */
    public int markResumed(long id, String approver, String resumedAtUtc) {
        return jdbc.update("UPDATE release_freeze_record SET status = 'RESUMED', approved_by = ?,"
                + " resumed_at_utc = ?, active_version = NULL, updated_at = CURRENT_TIMESTAMP"
                + " WHERE id = ? AND status = 'FROZEN' AND resume_requested_at_utc IS NOT NULL",
                approver, resumedAtUtc, id);
    }
}
