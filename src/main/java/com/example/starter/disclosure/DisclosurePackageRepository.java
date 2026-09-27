package com.example.starter.disclosure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 披露包表访问。包行写入后业务字段不可变；交付/撤销只允许一次条件状态推进。
 * （case_key, package_version）与 package_key 均有唯一约束，失败事务回滚不占用版本号。
 */
@Repository
public class DisclosurePackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新披露包（初始状态 GENERATED）。
     */
    public void insert(DisclosurePackage pkg) {
        jdbc.update("""
                        INSERT INTO disclosure_package
                            (package_key, case_key, package_version, based_on_version, cutoff_at,
                             status, actor_id, evidence_count, summary_json, created_at,
                             delivered_at, delivered_by, revoke_reason, revoked_at, revoked_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL)
                        """,
                pkg.packageKey(), pkg.caseKey(), pkg.packageVersion(), pkg.basedOnVersion(),
                pkg.cutoffAt(), pkg.status().name(), pkg.actorId(), pkg.evidenceCount(),
                pkg.summaryJson(), pkg.createdAt());
    }

    /**
     * 按包指纹查询（不加锁），用于只读场景。
     */
    public Optional<DisclosurePackage> findByKey(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ?", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按包指纹查询并锁定包行，用于交付/撤销等状态推进。
     */
    public Optional<DisclosurePackage> findByKeyForUpdate(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ? FOR UPDATE",
                ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按版本顺序列出案件全部包（含已交付与已撤销）。
     */
    public List<DisclosurePackage> findByCase(String caseKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package WHERE case_key = ? ORDER BY package_version",
                ROW_MAPPER, caseKey);
    }

    /**
     * 条件交付：仅 GENERATED 包可推进为 DELIVERED。
     *
     * @return 是否成功（false 表示已交付、已撤销或被并发先行处理）
     */
    public boolean markDelivered(String packageKey, String actorId, LocalDateTime at) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, delivered_at = ?, delivered_by = ?
                        WHERE package_key = ? AND status = ?
                        """,
                DisclosurePackageStatus.DELIVERED.name(), at, actorId,
                packageKey, DisclosurePackageStatus.GENERATED.name());
        return updated == 1;
    }

    /**
     * 条件撤销：仅 GENERATED 包可撤销并写入原因。
     *
     * @return 是否成功（false 表示已交付、已撤销或被并发先行处理）
     */
    public boolean markRevoked(String packageKey, String reason, String actorId, LocalDateTime at) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, revoke_reason = ?, revoked_at = ?, revoked_by = ?
                        WHERE package_key = ? AND status = ?
                        """,
                DisclosurePackageStatus.REVOKED.name(), reason, at, actorId,
                packageKey, DisclosurePackageStatus.GENERATED.name());
        return updated == 1;
    }

    private static final class PackageRowMapper implements RowMapper<DisclosurePackage> {
        @Override
        public DisclosurePackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DisclosurePackage(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("case_key"),
                    rs.getInt("package_version"),
                    rs.getObject("based_on_version", Integer.class),
                    rs.getObject("cutoff_at", LocalDateTime.class),
                    DisclosurePackageStatus.valueOf(rs.getString("status")),
                    rs.getString("actor_id"),
                    rs.getInt("evidence_count"),
                    rs.getString("summary_json"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("delivered_at", LocalDateTime.class),
                    rs.getString("delivered_by"),
                    rs.getString("revoke_reason"),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoked_by"));
        }
    }
}
