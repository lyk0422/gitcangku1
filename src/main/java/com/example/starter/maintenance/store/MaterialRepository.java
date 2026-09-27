package com.example.starter.maintenance.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.Material;

/**
 * 材料批次台账数据访问层：登记后仅允许撤销（状态翻转），行不删除。
 */
@Repository
public class MaterialRepository {

    private final JdbcTemplate jdbc;

    public MaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(java.time.ZoneOffset.UTC);
    }

    public void insert(Material material, String requestId) {
        jdbc.update("INSERT INTO material (batch_code, status, description, revoke_reason,"
                        + " request_id, created_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?, NULL)",
                material.batchCode(), material.active() ? Material.ACTIVE : Material.REVOKED,
                material.description(), material.revokeReason(), requestId, utc(material.createdAt()));
    }

    public Optional<Material> find(String batchCode) {
        List<Material> rows = jdbc.query(
                "SELECT batch_code, status, description, revoke_reason, created_at, revoked_at"
                        + " FROM material WHERE batch_code = ?",
                (rs, n) -> new Material(
                        rs.getString("batch_code"),
                        Material.ACTIVE.equals(rs.getString("status")),
                        rs.getString("description"),
                        rs.getString("revoke_reason"),
                        rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant(),
                        rs.getObject("revoked_at", java.time.OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("revoked_at", java.time.OffsetDateTime.class).toInstant()),
                batchCode);
        return rows.stream().findFirst();
    }

    /** 仅当材料仍为 ACTIVE 时翻转状态，返回受影响行数。 */
    public int markRevoked(String batchCode, String reason, Instant revokedAt) {
        return jdbc.update("UPDATE material SET status = ?, revoke_reason = ?, revoked_at = ?"
                        + " WHERE batch_code = ? AND status = ?",
                Material.REVOKED, reason, utc(revokedAt), batchCode, Material.ACTIVE);
    }
}
