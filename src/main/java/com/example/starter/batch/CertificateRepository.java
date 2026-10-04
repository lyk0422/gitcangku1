package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证声明、批次绑定、放行快照与合批关系的持久化层：所有 SQL 参数化；
 * 时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class CertificateRepository {

    /**
     * certificate_declaration 表行记录；withdrawnBy/withdrawnAt 未撤回时为 null。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String materialCode,
                                 String batchNoPrefix, int priority, String validFrom, String validTo,
                                 String status, String publishedBy, String publishedAt,
                                 String withdrawnBy, String withdrawnAt) {
    }

    /**
     * batch_certificate_binding 表行记录。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int declarationVersion,
                             String matchedPrefix, String boundBy, String boundAt) {
    }

    /**
     * release_snapshot 表行记录：放行时点固化，创建后不改写。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String declarationKey,
                                     int declarationVersion, String matchedPrefix, String releasedAt) {
    }

    /**
     * batch_merge 表行记录。
     */
    public record MergeRow(long id, String targetBatchKey, String parentBatchKey, int seq,
                           String createdAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("material_code"), rs.getString("batch_no_prefix"), rs.getInt("priority"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getString("status"),
            rs.getString("published_by"), rs.getString("published_at"),
            rs.getString("withdrawn_by"), rs.getString("withdrawn_at"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("declaration_version"), rs.getString("matched_prefix"),
            rs.getString("bound_by"), rs.getString("bound_at"));

    private static final RowMapper<ReleaseSnapshotRow> SNAPSHOT_MAPPER = (rs, n) ->
            new ReleaseSnapshotRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("declaration_key"), rs.getInt("declaration_version"),
                    rs.getString("matched_prefix"), rs.getString("released_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("target_batch_key"), rs.getString("parent_batch_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DeclarationRow> findDeclaration(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ?",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 行锁读取声明版本：放行/合批校验与撤回按事务提交顺序串行裁决。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, version, material_code,"
                        + " batch_no_prefix, priority, valid_from, valid_to, status, published_by,"
                        + " published_at, withdrawn_by, withdrawn_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.materialCode(), row.batchNoPrefix(),
                row.priority(), row.validFrom(), row.validTo(), row.status(), row.publishedBy(),
                row.publishedAt(), row.withdrawnBy(), row.withdrawnAt());
    }

    public void markWithdrawn(String declarationKey, int version, String withdrawnBy,
                              String withdrawnAt) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN', withdrawn_by = ?,"
                        + " withdrawn_at = ? WHERE declaration_key = ? AND version = ?",
                withdrawnBy, withdrawnAt, declarationKey, version);
    }

    /**
     * 某物料的全部声明版本（含已撤回），按声明键、版本稳定排序。
     */
    public List<DeclarationRow> findDeclarationsByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE material_code = ?"
                        + " ORDER BY declaration_key, version", DECLARATION_MAPPER, materialCode);
    }

    /**
     * 全部声明版本，按物料、声明键、版本稳定排序。
     */
    public List<DeclarationRow> findAllDeclarations() {
        return jdbc.query("SELECT * FROM certificate_declaration"
                + " ORDER BY material_code, declaration_key, version", DECLARATION_MAPPER);
    }

    /**
     * 某声明键的全部版本历史，按版本号稳定排序；撤回信息随版本记录，不改写。
     */
    public List<DeclarationRow> findDeclarationHistory(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?"
                + " ORDER BY version", DECLARATION_MAPPER, declarationKey);
    }
    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ?",
                BINDING_MAPPER, batchKey).stream().findFirst();
    }

    /**
     * 行锁读取批次当前绑定：绑定/换绑与放行在同一批次上按提交顺序串行。
     */
    public Optional<BindingRow> findBindingForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ? FOR UPDATE",
                BINDING_MAPPER, batchKey).stream().findFirst();
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_certificate_binding (batch_key, declaration_key,"
                        + " declaration_version, matched_prefix, bound_by, bound_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.boundBy(), row.boundAt());
    }

    /**
     * 放行前换绑：整行替换，旧绑定不保留（历史证据以放行快照为准）。
     */
    public void replaceBinding(BindingRow row) {
        jdbc.update("UPDATE batch_certificate_binding SET declaration_key = ?,"
                        + " declaration_version = ?, matched_prefix = ?, bound_by = ?, bound_at = ?"
                        + " WHERE batch_key = ?",
                row.declarationKey(), row.declarationVersion(), row.matchedPrefix(),
                row.boundBy(), row.boundAt(), row.batchKey());
    }

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey).stream().findFirst();
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, declaration_key, declaration_version,"
                        + " matched_prefix, released_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.releasedAt());
    }

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (target_batch_key, parent_batch_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.targetBatchKey(), row.parentBatchKey(), row.seq(), row.createdAt());
    }

    /**
     * 某合批新批次的全部父批业务键，按请求顺序稳定返回。
     */
    public List<String> findMergeParents(String targetBatchKey) {
        return jdbc.queryForList("SELECT parent_batch_key FROM batch_merge"
                        + " WHERE target_batch_key = ? ORDER BY seq", String.class, targetBatchKey);
    }
}