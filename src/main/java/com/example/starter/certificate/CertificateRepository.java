package com.example.starter.certificate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证声明、批次绑定、放行快照与合批血缘的持久化层：所有 SQL 参数化；
 * 时间以 ISO-8601 UTC 字符串存取；声明/绑定/快照/血缘记录创建后不可改写（撤回仅更新声明自身状态列）。
 */
@Repository
public class CertificateRepository {

    /**
     * certificate_declaration 表行记录；withdrawnAt/withdrawnBy/withdrawReason 为 null 表示未撤回。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String materialCode,
                                 String batchNoPrefix, int priority, String validFrom, String validTo,
                                 String status, String publishedBy, String createdAt,
                                 String withdrawnAt, String withdrawnBy, String withdrawReason) {
    }

    /**
     * batch_certificate_binding 表行记录。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int declarationVersion,
                             String matchedPrefix, String boundBy, String createdAt) {
    }

    /**
     * release_snapshot 表行记录。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String declarationKey,
                                     int declarationVersion, String matchedPrefix,
                                     String releasedAt, String createdAt) {
    }

    /**
     * merge_lineage 表行记录。
     */
    public record MergeLineageRow(long id, String childKey, String parentKey, int seq,
                                  String createdAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("material_code"), rs.getString("batch_no_prefix"), rs.getInt("priority"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getString("status"),
            rs.getString("published_by"), rs.getString("created_at"),
            rs.getString("withdrawn_at"), rs.getString("withdrawn_by"),
            rs.getString("withdraw_reason"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("declaration_version"), rs.getString("matched_prefix"),
            rs.getString("bound_by"), rs.getString("created_at"));

    private static final RowMapper<ReleaseSnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new ReleaseSnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("declaration_version"), rs.getString("matched_prefix"),
            rs.getString("released_at"), rs.getString("created_at"));

    private static final RowMapper<MergeLineageRow> MERGE_LINEAGE_MAPPER = (rs, n) -> new MergeLineageRow(
            rs.getLong("id"), rs.getString("child_key"), rs.getString("parent_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, version, material_code,"
                        + " batch_no_prefix, priority, valid_from, valid_to, status, published_by,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.materialCode(), row.batchNoPrefix(),
                row.priority(), row.validFrom(), row.validTo(), row.status(), row.publishedBy(),
                row.createdAt());
    }

    public Optional<DeclarationRow> findDeclaration(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ?",
                DECLARATION_MAPPER, declarationKey, version)
                .stream().findFirst();
    }

    /**
     * 行锁读取声明版本，串行化 撤回/绑定/放行 对同一声明的并发判定，按事务提交顺序裁决。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationKey, version)
                .stream().findFirst();
    }

    /**
     * 某物料的全部声明版本，按 声明键、版本号 稳定排序。
     */
    public List<DeclarationRow> findDeclarationsByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE material_code = ?"
                        + " ORDER BY declaration_key, version", DECLARATION_MAPPER, materialCode);
    }

    /**
     * 某声明键的全部版本，按版本号稳定排序。
     */
    public List<DeclarationRow> findDeclarationsByKey(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?"
                        + " ORDER BY version", DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 全部声明版本，按 声明键、版本号 稳定排序。
     */
    public List<DeclarationRow> findAllDeclarations() {
        return jdbc.query("SELECT * FROM certificate_declaration ORDER BY declaration_key, version",
                DECLARATION_MAPPER);
    }

    /**
     * 标记撤回：仅更新声明自身状态列，历史绑定与放行快照不改写。
     */
    public void markWithdrawn(String declarationKey, int version, String withdrawnAt,
                              String withdrawnBy, String reason) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN', withdrawn_at = ?,"
                        + " withdrawn_by = ?, withdraw_reason = ?"
                        + " WHERE declaration_key = ? AND version = ?",
                withdrawnAt, withdrawnBy, reason, declarationKey, version);
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_certificate_binding (batch_key, declaration_key,"
                        + " declaration_version, matched_prefix, bound_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.boundBy(), row.createdAt());
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ?",
                BINDING_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, declaration_key, declaration_version,"
                        + " matched_prefix, released_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.releasedAt(), row.createdAt());
    }

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertMergeLineage(MergeLineageRow row) {
        jdbc.update("INSERT INTO merge_lineage (child_key, parent_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.childKey(), row.parentKey(), row.seq(), row.createdAt());
    }

    /**
     * 某合批产物的全部父批关系，按请求顺序稳定排序。
     */
    public List<MergeLineageRow> findMergeLineageByChild(String childKey) {
        return jdbc.query("SELECT * FROM merge_lineage WHERE child_key = ? ORDER BY seq",
                MERGE_LINEAGE_MAPPER, childKey);
    }
}
