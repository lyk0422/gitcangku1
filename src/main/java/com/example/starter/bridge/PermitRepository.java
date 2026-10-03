package com.example.starter.bridge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 通行许可仓库：全部时间字段以 UTC 的 LocalDateTime 落库。
 */
@Repository
public class PermitRepository {

    private static final String COLUMNS = "id, request_id, plate_no, mass_kg, planned_entry_at,"
            + " window_start, window_end, status, entered_at, exited_at,"
            + " enter_request_id, exit_request_id, cancel_request_id, created_at, updated_at";

    private final JdbcClient jdbc;

    public PermitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按签发幂等键查询许可。
     */
    public Optional<BridgePermit> findByRequestId(String requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bridge_permit WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 按主键查询许可。
     */
    public Optional<BridgePermit> findById(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bridge_permit WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 以行锁按主键查询许可，串行化同一许可上的进桥/出桥/撤销竞争。
     */
    public Optional<BridgePermit> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bridge_permit WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 查询与新窗口 [start, end) 冲突的活跃许可：已签发按预计窗口相交判断；
     * 已进桥许可自实际进桥时刻起持续占用，直到实际出桥。
     */
    public List<BridgePermit> findActiveOverlapping(Instant start, Instant end) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bridge_permit"
                        + " WHERE (status = 'APPROVED' AND window_start < :end AND window_end > :start)"
                        + " OR (status = 'ENTERED' AND entered_at < :end)"
                        + " ORDER BY id")
                .param("start", toUtc(start))
                .param("end", toUtc(end))
                .query(this::mapRow)
                .list();
    }

    /**
     * 插入新许可并回填主键。
     */
    public BridgePermit insert(BridgePermit permit) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO bridge_permit (request_id, plate_no, mass_kg, planned_entry_at,"
                        + " window_start, window_end, status, created_at, updated_at)"
                        + " VALUES (:requestId, :plateNo, :massKg, :plannedEntryAt,"
                        + " :windowStart, :windowEnd, :status, :now, :now)")
                .param("requestId", permit.requestId())
                .param("plateNo", permit.plateNo())
                .param("massKg", permit.massKg())
                .param("plannedEntryAt", toUtc(permit.plannedEntryAt()))
                .param("windowStart", toUtc(permit.windowStart()))
                .param("windowEnd", toUtc(permit.windowEnd()))
                .param("status", permit.status().name())
                .param("now", toUtc(permit.createdAt()))
                .update(keys);
        long id = keys.getKey().longValue();
        return findById(id).orElseThrow();
    }

    /**
     * 确认进桥：置为 ENTERED 并记录实际进桥时刻与进桥幂等键。
     */
    public void markEntered(long id, String enterRequestId, Instant enteredAt, Instant now) {
        jdbc.sql("UPDATE bridge_permit SET status = 'ENTERED', entered_at = :enteredAt,"
                        + " enter_request_id = :enterRequestId, updated_at = :now WHERE id = :id")
                .param("enteredAt", toUtc(enteredAt))
                .param("enterRequestId", enterRequestId)
                .param("now", toUtc(now))
                .param("id", id)
                .update();
    }

    /**
     * 出桥释放：置为 EXITED 并记录实际出桥时刻与出桥幂等键。
     */
    public void markExited(long id, String exitRequestId, Instant exitedAt, Instant now) {
        jdbc.sql("UPDATE bridge_permit SET status = 'EXITED', exited_at = :exitedAt,"
                        + " exit_request_id = :exitRequestId, updated_at = :now WHERE id = :id")
                .param("exitedAt", toUtc(exitedAt))
                .param("exitRequestId", exitRequestId)
                .param("now", toUtc(now))
                .param("id", id)
                .update();
    }

    /**
     * 撤销许可：置为 CANCELLED 并记录撤销幂等键，预计窗口随之释放。
     */
    public void markCancelled(long id, String cancelRequestId, Instant now) {
        jdbc.sql("UPDATE bridge_permit SET status = 'CANCELLED',"
                        + " cancel_request_id = :cancelRequestId, updated_at = :now WHERE id = :id")
                .param("cancelRequestId", cancelRequestId)
                .param("now", toUtc(now))
                .param("id", id)
                .update();
    }

    /**
     * 按主键顺序列出全部许可（时间轴查询）。
     */
    public List<BridgePermit> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM bridge_permit ORDER BY id")
                .query(this::mapRow)
                .list();
    }

    private BridgePermit mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new BridgePermit(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("plate_no"),
                rs.getLong("mass_kg"),
                toInstant(rs.getTimestamp("planned_entry_at")),
                toInstant(rs.getTimestamp("window_start")),
                toInstant(rs.getTimestamp("window_end")),
                PermitStatus.valueOf(rs.getString("status")),
                toInstant(rs.getTimestamp("entered_at")),
                toInstant(rs.getTimestamp("exited_at")),
                rs.getString("enter_request_id"),
                rs.getString("exit_request_id"),
                rs.getString("cancel_request_id"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static LocalDateTime toUtc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}