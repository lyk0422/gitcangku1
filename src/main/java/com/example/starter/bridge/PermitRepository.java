package com.example.starter.bridge;

import java.sql.PreparedStatement;
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
 * 许可与拒绝记录的 JDBC 持久化；所有 SQL 使用参数绑定，时刻统一以 UTC 存取。
 */
@Repository
public class PermitRepository {

    private static final RowMapper<BridgePermit> PERMIT_MAPPER = (rs, rowNum) -> new BridgePermit(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("plate_no"),
            rs.getInt("total_mass_kg"),
            rs.getTimestamp("planned_entry_at").toInstant(),
            rs.getTimestamp("window_start_at").toInstant(),
            rs.getTimestamp("window_end_at").toInstant(),
            PermitStatus.valueOf(rs.getString("status")),
            toInstant(rs.getTimestamp("actual_entry_at")),
            toInstant(rs.getTimestamp("actual_exit_at")),
            rs.getString("entry_request_id"),
            rs.getString("exit_request_id"),
            rs.getString("cancel_request_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<BridgeRejection> REJECTION_MAPPER = (rs, rowNum) -> new BridgeRejection(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("plate_no"),
            rs.getInt("total_mass_kg"),
            rs.getTimestamp("planned_entry_at").toInstant(),
            rs.getString("reason_code"),
            rs.getString("reason_detail"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public PermitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /** 读取并锁定便桥配置单例行（SELECT ... FOR UPDATE），用于串行化许可签发。 */
    public BridgeState lockBridgeState() {
        return jdbcTemplate.queryForObject(
                "SELECT id, bridge_name, max_total_mass_kg, crossing_minutes FROM bridge_state WHERE id = 1 FOR UPDATE",
                (rs, rowNum) -> new BridgeState(
                        rs.getInt("id"),
                        rs.getString("bridge_name"),
                        rs.getInt("max_total_mass_kg"),
                        rs.getInt("crossing_minutes")));
    }

    /** 按签发幂等键查询许可。 */
    public Optional<BridgePermit> findByRequestId(String requestId) {
        List<BridgePermit> rows = jdbcTemplate.query(
                "SELECT * FROM bridge_permit WHERE request_id = ?", PERMIT_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /** 按主键查询许可。 */
    public Optional<BridgePermit> findById(long id) {
        List<BridgePermit> rows = jdbcTemplate.query(
                "SELECT * FROM bridge_permit WHERE id = ?", PERMIT_MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 查询仍占用桥梁的许可：已核准未进桥或已进桥未出桥。 */
    public List<BridgePermit> findOccupying() {
        return jdbcTemplate.query(
                "SELECT * FROM bridge_permit WHERE status IN ('APPROVED', 'ENTERED')",
                PERMIT_MAPPER);
    }

    /** 时间轴查询：全部许可按窗口起点升序。 */
    public List<BridgePermit> findAllOrderByWindow() {
        return jdbcTemplate.query(
                "SELECT * FROM bridge_permit ORDER BY window_start_at, id", PERMIT_MAPPER);
    }

    /** 插入新许可并返回含主键的完整记录。 */
    public BridgePermit insert(BridgePermit permit) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO bridge_permit (request_id, plate_no, total_mass_kg, planned_entry_at,"
                            + " window_start_at, window_end_at, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, permit.requestId());
            ps.setString(2, permit.plateNo());
            ps.setInt(3, permit.totalMassKg());
            ps.setTimestamp(4, Timestamp.from(permit.plannedEntryAt()));
            ps.setTimestamp(5, Timestamp.from(permit.windowStartAt()));
            ps.setTimestamp(6, Timestamp.from(permit.windowEndAt()));
            ps.setString(7, permit.status().name());
            ps.setTimestamp(8, Timestamp.from(permit.createdAt()));
            ps.setTimestamp(9, Timestamp.from(permit.updatedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow(() -> new IllegalStateException("许可插入后读取失败: " + id));
    }

    /** 条件更新：仅当许可处于 APPROVED 时置为 ENTERED，返回受影响行数。 */
    public int markEntered(long id, Instant entryAt, String entryRequestId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE bridge_permit SET status = 'ENTERED', actual_entry_at = ?,"
                        + " entry_request_id = ?, updated_at = ? WHERE id = ? AND status = 'APPROVED'",
                Timestamp.from(entryAt), entryRequestId, Timestamp.from(now), id);
    }

    /** 条件更新：仅当许可处于 ENTERED 时置为 EXITED，返回受影响行数。 */
    public int markExited(long id, Instant exitAt, String exitRequestId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE bridge_permit SET status = 'EXITED', actual_exit_at = ?,"
                        + " exit_request_id = ?, updated_at = ? WHERE id = ? AND status = 'ENTERED'",
                Timestamp.from(exitAt), exitRequestId, Timestamp.from(now), id);
    }

    /** 条件更新：仅当许可处于 APPROVED 时置为 CANCELLED，返回受影响行数。 */
    public int markCancelled(long id, String cancelRequestId, Instant now) {
        return jdbcTemplate.update(
                "UPDATE bridge_permit SET status = 'CANCELLED', cancel_request_id = ?,"
                        + " updated_at = ? WHERE id = ? AND status = 'APPROVED'",
                cancelRequestId, Timestamp.from(now), id);
    }

    /** 追加一条拒绝记录；拒绝不占用签发幂等键，允许同一请求键多次出现。 */
    public void insertRejection(BridgeRejection rejection) {
        jdbcTemplate.update(
                "INSERT INTO bridge_rejection (request_id, plate_no, total_mass_kg, planned_entry_at,"
                        + " reason_code, reason_detail, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rejection.requestId(),
                rejection.plateNo(),
                rejection.totalMassKg(),
                Timestamp.from(rejection.plannedEntryAt()),
                rejection.reasonCode(),
                rejection.reasonDetail(),
                Timestamp.from(rejection.createdAt()));
    }

    /** 查询全部拒绝记录，按创建时刻升序。 */
    public List<BridgeRejection> findAllRejections() {
        return jdbcTemplate.query(
                "SELECT * FROM bridge_rejection ORDER BY created_at, id", REJECTION_MAPPER);
    }

    /** 读取便桥配置单例行（不加锁），用于查询展示。 */
    public BridgeState findBridgeState() {
        return jdbcTemplate.queryForObject(
                "SELECT id, bridge_name, max_total_mass_kg, crossing_minutes FROM bridge_state WHERE id = 1",
                (rs, rowNum) -> new BridgeState(
                        rs.getInt("id"),
                        rs.getString("bridge_name"),
                        rs.getInt("max_total_mass_kg"),
                        rs.getInt("crossing_minutes")));
    }
}