package com.example.starter.bridge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 拒绝记录仓库：拒绝只追加、不占用签发幂等键。
 */
@Repository
public class RejectionRepository {

    private final JdbcClient jdbc;

    public RejectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条拒绝记录。
     */
    public void insert(String requestId, String plateNo, long massKg, Instant plannedEntryAt,
            RejectReason reason, String detail, Instant now) {
        jdbc.sql("INSERT INTO bridge_rejection (request_id, plate_no, mass_kg, planned_entry_at,"
                        + " reason, detail, created_at)"
                        + " VALUES (:requestId, :plateNo, :massKg, :plannedEntryAt, :reason, :detail, :now)")
                .param("requestId", requestId)
                .param("plateNo", plateNo)
                .param("massKg", massKg)
                .param("plannedEntryAt", LocalDateTime.ofInstant(plannedEntryAt, ZoneOffset.UTC))
                .param("reason", reason.name())
                .param("detail", detail)
                .param("now", LocalDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * 按主键顺序列出全部拒绝记录。
     */
    public List<RejectionRecord> findAll() {
        return jdbc.sql("SELECT id, request_id, plate_no, mass_kg, planned_entry_at, reason, detail, created_at"
                        + " FROM bridge_rejection ORDER BY id")
                .query(this::mapRow)
                .list();
    }

    private RejectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RejectionRecord(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("plate_no"),
                rs.getLong("mass_kg"),
                toInstant(rs.getTimestamp("planned_entry_at")),
                RejectReason.valueOf(rs.getString("reason")),
                rs.getString("detail"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }
}