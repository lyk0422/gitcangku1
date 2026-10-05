package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证声明、批次绑定与放行快照持久化层；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class CertificateRepository {

    /**
     * certificate_declaration 表行记录。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String materialCode,
                                 int priority, String validFrom, String validTo, String status,
                                 String actorId, String createdAt, String withdrawnAt) {
    }

    /**
     * certificate_binding 表行记录。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int version,
                             String materialCode, String matchedPrefix, String actorId,
                             String createdAt) {
    }

    /**
     * release_snapshot 表行记录。
     */
    public record SnapshotRow(long id, String batchKey, String declarationKey, int version,
                              String matchedPrefix, String releasedAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("material_code"), rs.getInt("priority"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getString("status"),
            rs.getString("actor_id"), rs.getString("created_at"), rs.getString("withdrawn_at"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("version"), rs.getString("material_code"), rs.getString("matched_prefix"),
            rs.getString("actor_id"), rs.getString("created_at"));

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new SnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("version"), rs.getString("matched_prefix"), rs.getString("released_at"));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, version, material_code,"
                        + " priority, valid_from, valid_to, status, actor_id, created_at, withdrawn_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.materialCode(), row.priority(),
                row.validFrom(), row.validTo(), row.status(), row.actorId(), row.createdAt(),
                row.withdrawnAt());
    }

    public void insertPrefix(String declarationKey, int version, String prefix, int seq) {
        jdbc.update("INSERT INTO declaration_prefix (declaration_key, version, prefix, seq)"
                        + " VALUES (?, ?, ?, ?)",
                declarationKey, version, prefix, seq);
    }

    public Optional<DeclarationRow> findDeclaration(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ?",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 行锁读取声明版本，串行化同一声明上的撤回/绑定/放行校验事务，按提交顺序裁决。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 某声明键的全部版本，按版本号升序（稳定排序）。
     */
    public List<DeclarationRow> findDeclarationsByKey(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?"
                + " ORDER BY version", DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 某物料下仍处于 PUBLISHED 的声明，用于发布时的前缀重叠校验。
     */
    public List<DeclarationRow> findPublishedByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE material_code = ? AND status = 'PUBLISHED' ORDER BY id",
                DECLARATION_MAPPER, materialCode);
    }

    /**
     * 声明版本的允许批次号前缀，按发布请求顺序（稳定排序）。
     */
    public List<String> findPrefixes(String declarationKey, int version) {
        return jdbc.queryForList("SELECT prefix FROM declaration_prefix"
                        + " WHERE declaration_key = ? AND version = ? ORDER BY seq",
                String.class, declarationKey, version);
    }

    public void markWithdrawn(String declarationKey, int version, String withdrawnAt) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN', withdrawn_at = ?"
                        + " WHERE declaration_key = ? AND version = ?",
                withdrawnAt, declarationKey, version);
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO certificate_binding (batch_key, declaration_key, version,"
                        + " material_code, matched_prefix, actor_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.version(), row.materialCode(),
                row.matchedPrefix(), row.actorId(), row.createdAt());
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM certificate_binding WHERE batch_key = ?",
                BINDING_MAPPER, batchKey).stream().findFirst();
    }

    public void insertSnapshot(SnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, declaration_key, version,"
                        + " matched_prefix, released_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.version(), row.matchedPrefix(),
                row.releasedAt());
    }

    public Optional<SnapshotRow> findSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey).stream().findFirst();
    }
}
