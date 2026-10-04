package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证域持久化层：声明版本、前缀、批次绑定、放行快照与合批关系。
 * 所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class CertificateRepository {

    /**
     * certificate_declaration 表行记录；withdrawnBy/withdrawnAt 未撤回时为 null。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String materialCode,
                                 String validFrom, String validTo, String status,
                                 String publishedBy, String publishedAt,
                                 String withdrawnBy, String withdrawnAt) {
    }

    /**
     * certificate_prefix 表行记录。
     */
    public record PrefixRow(long id, String declarationKey, int version, String prefix,
                            int priority, int seq) {
    }

    /**
     * batch_certificate_binding 表行记录；matchedPrefix 无匹配时为 null。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int version,
                             String materialCode, String matchedPrefix,
                             String boundBy, String boundAt) {
    }

    /**
     * release_snapshot 表行记录。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String declarationKey, int version,
                                     String matchedPrefix, String releasedAt) {
    }

    /**
     * batch_merge 表行记录：合批父子关系，创建后不可改写。
     */
    public record MergeRow(long id, String parentKey, String childKey, int seq, String createdAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("material_code"), rs.getString("valid_from"), rs.getString("valid_to"),
            rs.getString("status"), rs.getString("published_by"), rs.getString("published_at"),
            rs.getString("withdrawn_by"), rs.getString("withdrawn_at"));

    private static final RowMapper<PrefixRow> PREFIX_MAPPER = (rs, n) -> new PrefixRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("prefix"), rs.getInt("priority"), rs.getInt("seq"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("version"), rs.getString("material_code"), rs.getString("matched_prefix"),
            rs.getString("bound_by"), rs.getString("bound_at"));

    private static final RowMapper<ReleaseSnapshotRow> SNAPSHOT_MAPPER = (rs, n) -> new ReleaseSnapshotRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("version"), rs.getString("matched_prefix"), rs.getString("released_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("parent_key"), rs.getString("child_key"),
            rs.getInt("seq"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, version, material_code,"
                        + " valid_from, valid_to, status, published_by, published_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.materialCode(), row.validFrom(),
                row.validTo(), row.status(), row.publishedBy(), row.publishedAt());
    }

    public void insertPrefix(PrefixRow row) {
        jdbc.update("INSERT INTO certificate_prefix (declaration_key, version, prefix, priority, seq)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.prefix(), row.priority(), row.seq());
    }

    public Optional<DeclarationRow> findDeclaration(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ?",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 行锁读取声明版本，串行化同一声明上的撤回与放行读取。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 某声明键的全部版本，按 version 升序稳定排序。
     */
    public List<DeclarationRow> findVersions(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?"
                + " ORDER BY version", DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 某物料代码的全部声明版本（含已撤回），按 declaration_key、version 升序稳定排序。
     */
    public List<DeclarationRow> findByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE material_code = ?"
                + " ORDER BY declaration_key, version", DECLARATION_MAPPER, materialCode);
    }

    /**
     * 某物料代码是否存在任意状态的声明版本（含已撤回）。
     */
    public boolean existsForMaterial(String materialCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM certificate_declaration"
                + " WHERE material_code = ?", Integer.class, materialCode);
        return count != null && count > 0;
    }

    /**
     * 某声明版本的前缀，按 priority 降序、发布顺序 seq 升序稳定排序。
     */
    public List<PrefixRow> findPrefixes(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_prefix"
                        + " WHERE declaration_key = ? AND version = ?"
                        + " ORDER BY priority DESC, seq",
                PREFIX_MAPPER, declarationKey, version);
    }

    public void markWithdrawn(String declarationKey, int version, String withdrawnBy,
                              String withdrawnAt) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN',"
                        + " withdrawn_by = ?, withdrawn_at = ?"
                        + " WHERE declaration_key = ? AND version = ?",
                withdrawnBy, withdrawnAt, declarationKey, version);
    }
    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_certificate_binding (batch_key, declaration_key, version,"
                        + " material_code, matched_prefix, bound_by, bound_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.version(), row.materialCode(),
                row.matchedPrefix(), row.boundBy(), row.boundAt());
    }

    /**
     * 某批次的全部绑定，按绑定提交顺序（id 升序）稳定排序。
     */
    public List<BindingRow> findBindings(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ?"
                + " ORDER BY id", BINDING_MAPPER, batchKey);
    }

    public Optional<BindingRow> findBinding(String batchKey, String declarationKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding"
                        + " WHERE batch_key = ? AND declaration_key = ?",
                BINDING_MAPPER, batchKey, declarationKey).stream().findFirst();
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, declaration_key, version,"
                        + " matched_prefix, released_at) VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.version(),
                row.matchedPrefix(), row.releasedAt());
    }

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey).stream().findFirst();
    }

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (parent_key, child_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.parentKey(), row.childKey(), row.seq(), row.createdAt());
    }

    /**
     * 某合批新批的父批业务键，按合批请求顺序（seq 升序）稳定排序。
     */
    public List<String> findMergeParents(String childKey) {
        return jdbc.queryForList("SELECT parent_key FROM batch_merge WHERE child_key = ?"
                + " ORDER BY seq", String.class, childKey);
    }
}