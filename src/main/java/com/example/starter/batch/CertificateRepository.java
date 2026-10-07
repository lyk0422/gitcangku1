package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 合格证声明域持久化层：声明版本、批次绑定、放行快照、合批血缘与声明串行锁。
 * 所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class CertificateRepository {

    /**
     * declaration 表行记录：一个声明版本，发布后不可改写，只可撤回。
     */
    public record DeclarationRow(long id, String declarationKey, int version, String materialCode,
                                 String batchNoPrefix, int priority, String validFrom, String validTo,
                                 String status, String operator, String createdAt,
                                 String withdrawnAt) {
    }

    /**
     * certificate_binding 表行记录：批次与声明版本的绑定。
     */
    public record BindingRow(long id, String batchKey, String declarationKey, int declarationVersion,
                             String materialCode, String batchNoPrefix, String boundBy,
                             String createdAt) {
    }

    /**
     * release_snapshot 表行记录：放行时固化的声明版本、匹配前缀与时点。
     */
    public record ReleaseSnapshotRow(long id, String batchKey, String declarationKey,
                                     int declarationVersion, String matchedPrefix,
                                     String releasedAt, String createdAt) {
    }

    /**
     * batch_merge 表行记录：合批父子关系，创建后不可改写。
     */
    public record MergeRow(long id, String childKey, String parentKey, int seq, String createdAt) {
    }

    private static final RowMapper<DeclarationRow> DECLARATION_MAPPER = (rs, n) -> new DeclarationRow(
            rs.getLong("id"), rs.getString("declaration_key"), rs.getInt("version"),
            rs.getString("material_code"), rs.getString("batch_no_prefix"), rs.getInt("priority"),
            rs.getString("valid_from"), rs.getString("valid_to"), rs.getString("status"),
            rs.getString("operator"), rs.getString("created_at"), rs.getString("withdrawn_at"));

    private static final RowMapper<BindingRow> BINDING_MAPPER = (rs, n) -> new BindingRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("declaration_key"),
            rs.getInt("declaration_version"), rs.getString("material_code"),
            rs.getString("batch_no_prefix"), rs.getString("bound_by"), rs.getString("created_at"));

    private static final RowMapper<ReleaseSnapshotRow> SNAPSHOT_MAPPER = (rs, n) ->
            new ReleaseSnapshotRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("declaration_key"), rs.getInt("declaration_version"),
                    rs.getString("matched_prefix"), rs.getString("released_at"),
                    rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public CertificateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 持有声明域全局串行锁：发布与撤回在同一行上 SELECT ... FOR UPDATE，按提交顺序裁决。
     */
    public void lockDeclarations() {
        jdbc.queryForObject("SELECT id FROM declaration_lock WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    public void insertDeclaration(DeclarationRow row) {
        jdbc.update("INSERT INTO declaration (declaration_key, version, material_code,"
                        + " batch_no_prefix, priority, valid_from, valid_to, status, operator,"
                        + " created_at, withdrawn_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.declarationKey(), row.version(), row.materialCode(), row.batchNoPrefix(),
                row.priority(), row.validFrom(), row.validTo(), row.status(), row.operator(),
                row.createdAt(), row.withdrawnAt());
    }

    public Optional<DeclarationRow> findDeclaration(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM declaration WHERE declaration_key = ? AND version = ?",
                        DECLARATION_MAPPER, declarationKey, version)
                .stream().findFirst();
    }

    /**
     * 行锁读取声明版本：绑定、合批与放行门禁借此与撤回按事务提交顺序裁决。
     */
    public Optional<DeclarationRow> findDeclarationForUpdate(String declarationKey, int version) {
        return jdbc.query("SELECT * FROM declaration WHERE declaration_key = ? AND version = ?"
                        + " FOR UPDATE", DECLARATION_MAPPER, declarationKey, version)
                .stream().findFirst();
    }

    /**
     * 某声明业务键的全部版本，按版本号升序。
     */
    public List<DeclarationRow> findVersions(String declarationKey) {
        return jdbc.query("SELECT * FROM declaration WHERE declaration_key = ? ORDER BY version",
                DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 行锁读取某声明业务键的全部版本，串行化撤回与发布。
     */
    public List<DeclarationRow> findVersionsForUpdate(String declarationKey) {
        return jdbc.query("SELECT * FROM declaration WHERE declaration_key = ?"
                        + " ORDER BY version FOR UPDATE", DECLARATION_MAPPER, declarationKey);
    }

    /**
     * 全部声明版本，按 (declaration_key, version) 稳定排序。
     */
    public List<DeclarationRow> findAllDeclarations() {
        return jdbc.query("SELECT * FROM declaration ORDER BY declaration_key, version",
                DECLARATION_MAPPER);
    }

    /**
     * 某物料下全部 ACTIVE 声明版本，用于发布时的前缀重叠检查。
     */
    public List<DeclarationRow> findActiveByMaterial(String materialCode) {
        return jdbc.query("SELECT * FROM declaration WHERE material_code = ? AND status = 'ACTIVE'",
                DECLARATION_MAPPER, materialCode);
    }

    /**
     * 撤回某声明业务键的全部 ACTIVE 版本，返回更新行数。
     */
    public int withdraw(String declarationKey, String withdrawnAt) {
        return jdbc.update("UPDATE declaration SET status = 'WITHDRAWN', withdrawn_at = ?"
                + " WHERE declaration_key = ? AND status = 'ACTIVE'", withdrawnAt, declarationKey);
    }

    public void insertBinding(BindingRow row) {
        jdbc.update("INSERT INTO certificate_binding (batch_key, declaration_key,"
                        + " declaration_version, material_code, batch_no_prefix, bound_by, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.declarationKey(), row.declarationVersion(), row.materialCode(),
                row.batchNoPrefix(), row.boundBy(), row.createdAt());
    }

    /**
     * 某批次的全部绑定，按绑定顺序（id）稳定排序。
     */
    public List<BindingRow> findBindings(String batchKey) {
        return jdbc.query("SELECT * FROM certificate_binding WHERE batch_key = ? ORDER BY id",
                BINDING_MAPPER, batchKey);
    }

    public Optional<BindingRow> findBinding(String batchKey, String declarationKey, int version) {
        return jdbc.query("SELECT * FROM certificate_binding WHERE batch_key = ?"
                        + " AND declaration_key = ? AND declaration_version = ?",
                        BINDING_MAPPER, batchKey, declarationKey, version)
                .stream().findFirst();
    }

    /**
     * 某批次全部绑定对应的声明版本（含已撤回），用于放行门禁在内存中判定。
     */
    public List<DeclarationRow> findBoundDeclarations(String batchKey) {
        return jdbc.query("SELECT d.* FROM declaration d JOIN certificate_binding b"
                        + " ON d.declaration_key = b.declaration_key"
                        + " AND d.version = b.declaration_version"
                        + " WHERE b.batch_key = ? ORDER BY b.id",
                DECLARATION_MAPPER, batchKey);
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

    public void insertMerge(MergeRow row) {
        jdbc.update("INSERT INTO batch_merge (child_key, parent_key, seq, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                row.childKey(), row.parentKey(), row.seq(), row.createdAt());
    }
}
