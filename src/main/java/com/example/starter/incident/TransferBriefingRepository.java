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
 * 交接简报与回读确认的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；(transfer_id, version) 与
 * (briefing_id, reader) 唯一约束兜底并发重复写入。
 */
@Repository
public class TransferBriefingRepository {

    private final JdbcTemplate jdbc;

    public TransferBriefingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TransferBriefing> BRIEFING_MAPPER = (rs, n) -> mapBriefing(rs);
    private static final RowMapper<TransferReadback> READBACK_MAPPER = (rs, n) -> new TransferReadback(
            rs.getLong("id"), rs.getLong("briefing_id"), rs.getString("reader"),
            rs.getString("digest"), rs.getTimestamp("created_at").toInstant());

    private static TransferBriefing mapBriefing(ResultSet rs) throws SQLException {
        return new TransferBriefing(rs.getLong("id"), rs.getLong("transfer_id"),
                rs.getLong("incident_id"), rs.getInt("version"), rs.getString("content"),
                rs.getString("digest"), rs.getString("note"),
                BriefingStatus.valueOf(rs.getString("status")), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入简报新版本，返回生成主键。
     */
    public long insert(TransferBriefing briefing) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO transfer_briefings (transfer_id, incident_id, version, content,"
                            + " digest, note, status, created_by, created_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, briefing.transferId());
            ps.setLong(2, briefing.incidentId());
            ps.setInt(3, briefing.version());
            ps.setString(4, briefing.content());
            ps.setString(5, briefing.digest());
            ps.setString(6, briefing.note());
            ps.setString(7, briefing.status().name());
            ps.setString(8, briefing.createdBy());
            ps.setTimestamp(9, Timestamp.from(briefing.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询简报。
     */
    public Optional<TransferBriefing> findById(long id) {
        List<TransferBriefing> rows = jdbc.query("SELECT * FROM transfer_briefings WHERE id = ?",
                BRIEFING_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 查询交接单当前最新版本的简报。
     */
    public Optional<TransferBriefing> findLatestByTransfer(long transferId) {
        List<TransferBriefing> rows = jdbc.query(
                "SELECT * FROM transfer_briefings WHERE transfer_id = ?"
                        + " ORDER BY version DESC LIMIT 1",
                BRIEFING_MAPPER, transferId);
        return rows.stream().findFirst();
    }

    /**
     * 交接成功同事务冻结简报，内容与回读人不再变更。
     */
    public void freeze(long id) {
        jdbc.update("UPDATE transfer_briefings SET status = 'FROZEN' WHERE id = ?", id);
    }

    /**
     * 查询某回读人对指定简报的确认记录。
     */
    public Optional<TransferReadback> findReadback(long briefingId, String reader) {
        List<TransferReadback> rows = jdbc.query(
                "SELECT * FROM transfer_readbacks WHERE briefing_id = ? AND reader = ?",
                READBACK_MAPPER, briefingId, reader);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定简报的回读确认（仅接班人可确认，至多一条）。
     */
    public Optional<TransferReadback> findReadbackByBriefing(long briefingId) {
        List<TransferReadback> rows = jdbc.query(
                "SELECT * FROM transfer_readbacks WHERE briefing_id = ? LIMIT 1",
                READBACK_MAPPER, briefingId);
        return rows.stream().findFirst();
    }

    /**
     * 写入回读确认，返回生成主键。
     */
    public long insertReadback(TransferReadback readback) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO transfer_readbacks (briefing_id, reader, digest, created_at)"
                            + " VALUES (?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, readback.briefingId());
            ps.setString(2, readback.reader());
            ps.setString(3, readback.digest());
            ps.setTimestamp(4, Timestamp.from(readback.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
}