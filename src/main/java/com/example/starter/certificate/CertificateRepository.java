package com.example.starter.certificate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class CertificateRepository {

    /**
     * certificate_declaration 表行记录；前缀逐行存于 certificate_declaration_prefix。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String supplierId,
                                 String materialCode, int priority, String validFrom, String validTo,
                                 String status, String commandKey, String createdAt,
                                 String withdrawnAt, String withdrawnBy) {
    }

    /**
     * batch_certificate_binding 表行记录：批次当前绑定的声明版本。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int declarationVersion,
                             String matchedPrefix, String boundBy, String boundAt,
                             String commandKey) {
    }

    /**
     * release_snapshot 表行记录：放行时点固化的业务证据，随后续操作不改写。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String declarationKey,
                                     int declarationVersion, String matchedPrefix,
                                     String releasedAt) {
    }

    /**
     * batch_merge 表行记录：合批父子关系，创建后不可改写。
     */
    public record MergeRow(long id, String childKey, String parentKey, int seq, String createdAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("supplier_id"), rs.getString("material_code"), rs.getInt("priority"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getString("status"),
            rs.getString("command_key"), rs.getString("created_at"),
            rs.getString("withdrawn_at"), rs.getString("withdrawn_by"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("declaration_version"), rs.getString("matched_prefix"),
            rs.getString("bound_by"), rs.getString("bound_at"), rs.getString("command_key"));

    private static final RowMapper<ReleaseSnapshotRow> SNAPSHOT_MAPPER = (rs, n) ->
            new ReleaseSnapshotRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("declaration_key"), rs.getInt("declaration_version"),
                    rs.getString("matched_prefix"), rs.getString("released_at"));

    private static final RowMapper<MergeRow> MERGE_MAPPER = (rs, n) -> new MergeRow(
            rs.getLong("id"), rs.getString("child_key"), rs.getString("parent_key"),
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
     * 行锁读取声明版本，串行化发布/撤回/绑定/合批/放行对该版本的并发裁决。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE declaration_key = ? AND version = ? FOR UPDATE",
                DECLARATION_MAPPER, declarationKey, version).stream().findFirst();
    }

    /**
     * 某声明业务键的全部版本，按版本号升序（稳定排序）。
     */
    public List<DeclarationRow> findVersions(String declarationKey) {
        return jdbc.query("SELECT * FROM certificate_declaration WHERE declaration_key = ?"
                + " ORDER BY version", DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 全部声明版本（诊断用），按业务键、版本号稳定排序。
     */
    public List<DeclarationRow> findAllDeclarations() {
        return jdbc.query("SELECT * FROM certificate_declaration"
                + " ORDER BY declaration_key, version", DECLARATION_MAPPER);
    }

    /**
     * 某物料当前 ACTIVE 的声明版本（前缀重叠校验用），按业务键、版本号稳定排序。
     */
    public List<DeclarationRow> findActiveByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM certificate_declaration"
                        + " WHERE material_code = ? AND status = 'ACTIVE'"
                        + " ORDER BY declaration_key, version",
                DECLARATION_MAPPER, materialCode);
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO certificate_declaration (declaration_key, version, supplier_id,"
                        + " material_code, priority, valid_from, valid_to, status, command_key,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.supplierId(), row.materialCode(),
                row.priority(), row.validFrom(), row.validTo(), row.status(), row.commandKey(),
                row.createdAt());
    }

    public void markWithdrawn(String declarationKey, int version, String withdrawnBy,
                              String withdrawnAt) {
        jdbc.update("UPDATE certificate_declaration SET status = 'WITHDRAWN', withdrawn_by = ?,"
                        + " withdrawn_at = ? WHERE declaration_key = ? AND version = ?",
                withdrawnBy, withdrawnAt, declarationKey, version);
    }

    public void insertPrefix(String declarationKey, int version, String prefix, int seq) {
        jdbc.update("INSERT INTO certificate_declaration_prefix"
                        + " (declaration_key, declaration_version, prefix, seq)"
                        + " VALUES (?, ?, ?, ?)",
                declarationKey, version, prefix, seq);
    }

    /**
     * 某声明版本的允许批次号前缀，按发布请求中的顺序（稳定排序）。
     */
    public List<String> findPrefixes(String declarationKey, int version) {
        return jdbc.queryForList("SELECT prefix FROM certificate_declaration_prefix"
                        + " WHERE declaration_key = ? AND declaration_version = ? ORDER BY seq",
                String.class, declarationKey, version);
    }

    public Optional<BindingRow> findBinding(String batchKey) {
        return jdbc.query("SELECT * FROM batch_certificate_binding WHERE batch_key = ?",
                BINDING_MAPPER, batchKey).stream().findFirst();
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO batch_certificate_binding (batch_key, declaration_key,"
                        + " declaration_version, matched_prefix, bound_by, bound_at, command_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.boundBy(), row.boundAt(), row.commandKey());
    }

    /**
     * 放行前更换绑定：整行替换当前绑定；放行快照不受影响。
     */
    public void updateBinding(BindingRow row) {
        jdbc.update("UPDATE batch_certificate_binding SET declaration_key = ?,"
                        + " declaration_version = ?, matched_prefix = ?, bound_by = ?,"
                        + " bound_at = ?, command_key = ? WHERE batch_key = ?",
                row.declarationKey(), row.declarationVersion(), row.matchedPrefix(),
                row.boundBy(), row.boundAt(), row.commandKey(), row.batchKey());
    }

    public void insertReleaseSnapshot(ReleaseSnapshotRow row) {
        jdbc.update("INSERT INTO release_snapshot (batch_key, declaration_key,"
                        + " declaration_version, matched_prefix, released_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(),
                row.matchedPrefix(), row.releasedAt());
    }

    public Optional<ReleaseSnapshotRow> findReleaseSnapshot(String batchKey) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE batch_key = ?",
                SNAPSHOT_MAPPER, batchKey).stream().findFirst();
    }

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (child_key, parent_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.childKey(), row.parentKey(), row.seq(), row.createdAt());
    }

    /**
     * 某合批产物的全部父批关系，按请求顺序（稳定排序）。
     */
    public List<MergeRow> findMergeParents(String childKey) {
        return jdbc.query("SELECT * FROM batch_merge WHERE child_key = ? ORDER BY seq",
                MERGE_MAPPER, childKey);
    }
}