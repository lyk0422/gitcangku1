package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 交接简报仓储。所有写调用均处于先锁定事件行的写事务内：
 * 刷新与接收相对彼此及任务/升级/状态变更串行化，(transfer_id, version) 唯一约束兜底并发刷新。
 */
@Repository
public class TransferBriefingRepository {

    private final JdbcTemplate jdbc;

    public TransferBriefingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransferBriefing> MAPPER = (rs, n) -> map(rs);

    private static TransferBriefing map(ResultSet rs) throws SQLException {
        Timestamp readbackAt = rs.getTimestamp("readback_at");
        Timestamp frozenAt = rs.getTimestamp("frozen_at");
        return new TransferBriefing(
                rs.getLong("id"), rs.getLong("transfer_id"), rs.getLong("incident_id"),
                rs.getInt("version"), rs.getLong("incident_version"),
                rs.getString("incident_status"), rs.getString("note"),
                rs.getString("payload_json"), rs.getString("digest"),
                BriefingStatus.valueOf(rs.getString("status")),
                rs.getString("readback_by"),
                readbackAt == null ? null : readbackAt.toInstant(),
                rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(),
                frozenAt == null ? null : frozenAt.toInstant());
    }

    /**
     * 插入新简报（CURRENT），返回生成主键；(transfer_id, version) 唯一约束兜底并发刷新。
     */
    public long insert(TransferBriefing briefing) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO transfer_briefings (transfer_id, incident_id, version, incident_version,"
                            + " incident_status, note, payload_json, digest, status, readback_by,"
                            + " readback_at, created_by, created_at, frozen_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, briefing.transferId());
            ps.setLong(2, briefing.incidentId());
            ps.setInt(3, briefing.version());
            ps.setLong(4, briefing.incidentVersion());
            ps.setString(5, briefing.incidentStatus());
            ps.setString(6, briefing.note());
            ps.setString(7, briefing.payloadJson());
            ps.setString(8, briefing.digest());
            ps.setString(9, briefing.status().name());
            ps.setString(10, briefing.readbackBy());
            ps.setTimestamp(11, briefing.readbackAt() == null ? null : Timestamp.from(briefing.readbackAt()));
            ps.setString(12, briefing.createdBy());
            ps.setTimestamp(13, Timestamp.from(briefing.createdAt()));
            ps.setTimestamp(14, briefing.frozenAt() == null ? null : Timestamp.from(briefing.frozenAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询简报。
     */
    public Optional<TransferBriefing> findById(long id) {
        List<TransferBriefing> rows = jdbc.query("SELECT * FROM transfer_briefings WHERE id = ?",
                MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询交接单当前有效（CURRENT）的简报。
     */
    public Optional<TransferBriefing> findCurrentByTransfer(long transferId) {
        List<TransferBriefing> rows = jdbc.query(
                "SELECT * FROM transfer_briefings WHERE transfer_id = ? AND status = 'CURRENT'",
                MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 查询交接单全部简报，按版本升序返回（旧简报保留）。
     */
    public List<TransferBriefing> listByTransfer(long transferId) {
        return jdbc.query("SELECT * FROM transfer_briefings WHERE transfer_id = ? ORDER BY version",
                MAPPER, transferId);
    }

    /**
     * 将交接单当前 CURRENT 简报置为 SUPERSEDED（刷新时调用），返回更新行数。
     */
    public int supersedeCurrent(long transferId) {
        return jdbc.update("UPDATE transfer_briefings SET status = 'SUPERSEDED'"
                + " WHERE transfer_id = ? AND status = 'CURRENT'", transferId);
    }

    /**
     * 记录接班人回读确认；条件包含 readback_by IS NULL，重复回读更新行数为 0。
     */
    public int confirmReadback(long id, String readbackBy, Instant readbackAt) {
        return jdbc.update("UPDATE transfer_briefings SET readback_by = ?, readback_at = ?"
                        + " WHERE id = ? AND readback_by IS NULL",
                readbackBy, Timestamp.from(readbackAt), id);
    }

    /**
     * 交接成功时冻结简报（CURRENT → FROZEN），与指挥权切换同事务提交。
     */
    public int freeze(long id, Instant frozenAt) {
        return jdbc.update("UPDATE transfer_briefings SET status = 'FROZEN', frozen_at = ?"
                        + " WHERE id = ? AND status = 'CURRENT'",
                Timestamp.from(frozenAt), id);
    }
}