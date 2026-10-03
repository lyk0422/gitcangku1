package com.example.starter.snow;

import com.example.starter.snow.model.ClosureRec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 控制区状态与封闭命令的数据访问；状态行同时作为业务并发的行级锁。
 */
@Repository
public class RegionDao {

    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;

    public RegionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 确保控制区状态行存在（默认 OPEN）；并发插入冲突时忽略。
     */
    public void ensureRegion(String regionId, Instant now) {
        try {
            jdbc.update("INSERT INTO region_state (region_id, status, updated_at) VALUES (?, ?, ?)",
                    regionId, OPEN, Timestamp.from(now));
        } catch (DuplicateKeyException ignored) {
            // 已有状态行，无需处理
        }
    }

    /**
     * 以 FOR UPDATE 锁定并读取控制区状态，调用前必须先 ensureRegion。
     */
    public String lockRegionStatus(String regionId) {
        return jdbc.queryForObject(
                "SELECT status FROM region_state WHERE region_id = ? FOR UPDATE",
                String.class, regionId);
    }

    public void updateStatus(String regionId, String status, Instant now) {
        jdbc.update("UPDATE region_state SET status = ?, updated_at = ? WHERE region_id = ?",
                status, Timestamp.from(now), regionId);
    }

    public String currentStatus(String regionId) {
        List<String> rows = jdbc.query("SELECT status FROM region_state WHERE region_id = ?",
                (rs, i) -> rs.getString(1), regionId);
        return rows.isEmpty() ? OPEN : rows.get(0);
    }

    public long insertClosure(String regionId, String source, String reason, String requestId, Instant now) {
        jdbc.update("INSERT INTO closure_command (region_id, source, reason, request_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                regionId, source, reason, requestId, Timestamp.from(now));
        Long id = jdbc.queryForObject("SELECT MAX(id) FROM closure_command WHERE region_id = ?",
                Long.class, regionId);
        return id == null ? -1L : id;
    }

    public Optional<ClosureRec> findClosureByRequestId(String requestId) {
        List<ClosureRec> rows = jdbc.query(
                "SELECT * FROM closure_command WHERE request_id = ?", this::mapClosure, requestId);
        return rows.stream().findFirst();
    }

    public List<ClosureRec> findClosures(String regionId) {
        return jdbc.query("SELECT * FROM closure_command WHERE region_id = ? ORDER BY id",
                this::mapClosure, regionId);
    }

    private ClosureRec mapClosure(ResultSet rs, int rowNum) throws SQLException {
        Timestamp req = null;
        return new ClosureRec(rs.getLong("id"), rs.getString("region_id"), rs.getString("source"),
                rs.getString("reason"), rs.getString("request_id"), rs.getTimestamp("created_at").toInstant());
    }
}