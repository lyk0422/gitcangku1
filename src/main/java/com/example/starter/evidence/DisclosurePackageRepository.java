package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 披露包案件串行锁与披露包表访问。
 * 建包/增删修订先在事务内锁定案件行，包版本按事务提交顺序裁决；
 * 交付与撤销使用条件更新，已交付/已撤销状态不会被重复改写。
 */
@Repository
public class DisclosurePackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记案件锁行；已存在则忽略（INSERT IGNORE，MySQL/H2 MySQL 模式均支持）。
     */
    public void ensureCaseLock(String caseKey, LocalDateTime now) {
        jdbc.update("INSERT IGNORE INTO disclosure_case_lock (case_key, created_at) VALUES (?, ?)",
                caseKey, now);
    }

    /**
     * 锁定案件串行行（SELECT ... FOR UPDATE）。调用前须先 {@link #ensureCaseLock}。
     */
    public void lockCase(String caseKey) {
        jdbc.queryForObject("SELECT case_key FROM disclosure_case_lock WHERE case_key = ? FOR UPDATE",
                String.class, caseKey);
    }

    /**
     * 插入冻结披露包；package_key 或 (case_key, package_version) 冲突抛 DuplicateKeyException。
     */
    public void insert(DisclosurePackage pkg) {
        jdbc.update("""
                        INSERT INTO disclosure_package
                            (package_key, case_key, cutoff_at, package_version, status, operator_id,
                             evidence_count, package_digest, base_package_key,
                             delivered_by, delivered_at, revoke_reason, revoked_by, revoked_at,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                pkg.packageKey(), pkg.caseKey(), pkg.cutoffAt(), pkg.packageVersion(),
                pkg.status().name(), pkg.operatorId(), pkg.evidenceCount(), pkg.packageDigest(),
                pkg.basePackageKey(), pkg.createdAt(), pkg.updatedAt());
    }

    /**
     * 按包键查询（不加锁），用于只读场景。
     */
    public Optional<DisclosurePackage> findByKey(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ?", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按包键查询并锁定包行，用于交付/撤销状态变更。
     */
    public Optional<DisclosurePackage> findByKeyForUpdate(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ? FOR UPDATE",
                ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按版本倒序查询案件全部包（调用前须持有案件锁）。
     */
    public List<DisclosurePackage> findByCaseKey(String caseKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package WHERE case_key = ? ORDER BY package_version DESC, id DESC",
                ROW_MAPPER, caseKey);
    }

    /**
     * 查询案件当前最大包版本号；无包时返回 0（调用前须持有案件锁）。
     */
    public int findMaxVersion(String caseKey) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(package_version), 0) FROM disclosure_package WHERE case_key = ?",
                Integer.class, caseKey);
        return max == null ? 0 : max;
    }

    /**
     * 条件交付：仅 FROZEN 可交付。
     *
     * @return 是否更新成功（false 表示已交付或已撤销）
     */
    public boolean markDelivered(String packageKey, String actorId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, delivered_by = ?, delivered_at = ?, updated_at = ?
                        WHERE package_key = ? AND status = ?
                        """,
                DisclosurePackageStatus.DELIVERED.name(), actorId, now, now,
                packageKey, DisclosurePackageStatus.FROZEN.name());
        return updated == 1;
    }

    /**
     * 条件撤销：仅 FROZEN 可撤销并写原因。
     *
     * @return 是否更新成功（false 表示已交付或已撤销）
     */
    public boolean markRevoked(String packageKey, String actorId, String reason, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, revoke_reason = ?, revoked_by = ?, revoked_at = ?, updated_at = ?
                        WHERE package_key = ? AND status = ?
                        """,
                DisclosurePackageStatus.REVOKED.name(), reason, actorId, now, now,
                packageKey, DisclosurePackageStatus.FROZEN.name());
        return updated == 1;
    }

    private static final class PackageRowMapper implements RowMapper<DisclosurePackage> {
        @Override
        public DisclosurePackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DisclosurePackage(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("case_key"),
                    rs.getObject("cutoff_at", LocalDateTime.class),
                    rs.getInt("package_version"),
                    DisclosurePackageStatus.valueOf(rs.getString("status")),
                    rs.getString("operator_id"),
                    rs.getInt("evidence_count"),
                    rs.getString("package_digest"),
                    rs.getString("base_package_key"),
                    rs.getString("delivered_by"),
                    rs.getObject("delivered_at", LocalDateTime.class),
                    rs.getString("revoke_reason"),
                    rs.getString("revoked_by"),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
