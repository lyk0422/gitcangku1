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
 * 披露包主表访问。状态与内容变更必须先通过 {@link #findByKeyForUpdate} 锁定包行，
 * 保证建包、批量增删、交付、撤销按事务提交顺序生效。
 */
@Repository
public class DisclosurePackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新披露包，初始状态 FROZEN。package_key 与 (case_key, revision) 唯一约束兜底并发。
     */
    public void insert(String packageKey, String caseKey, int revision, LocalDateTime asOf,
                       String operatorId, String fingerprint, String evidenceKeys, int itemCount,
                       String summary, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO disclosure_package
                            (package_key, case_key, revision, as_of, status, operator_id, fingerprint,
                             evidence_keys, item_count, summary,
                             delivered_by, delivered_at, revoked_by, revoked_at, revoke_reason,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                packageKey, caseKey, revision, asOf, DisclosurePackageStatus.FROZEN.name(),
                operatorId, fingerprint, evidenceKeys, itemCount, summary, now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<DisclosurePackage> findByKey(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ?", ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定包行（SELECT ... FOR UPDATE），用于一切状态/内容变更。
     */
    public Optional<DisclosurePackage> findByKeyForUpdate(String packageKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE package_key = ? FOR UPDATE",
                ROW_MAPPER, packageKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询案件当前最高包版本；无记录时返回 0。
     */
    public int maxRevisionForCase(String caseKey) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(revision) FROM disclosure_package WHERE case_key = ?",
                Integer.class, caseKey);
        return max == null ? 0 : max;
    }

    /**
     * 批量增删后整体重写证物集合元数据（规范化列表、件数、摘要）。
     */
    public void updateItemsMeta(String packageKey, String evidenceKeys, int itemCount,
                                String summary, LocalDateTime now) {
        jdbc.update(
                "UPDATE disclosure_package SET evidence_keys = ?, item_count = ?, summary = ?,"
                        + " updated_at = ? WHERE package_key = ?",
                evidenceKeys, itemCount, summary, now, packageKey);
    }

    /**
     * 条件交付：仅当仍为 FROZEN 时置为 DELIVERED。
     *
     * @return 是否成功交付（false 表示已被并发事务先行变更状态）
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
     * 条件撤销：仅当仍为 FROZEN 时置为 REVOKED 并写入撤销原因。
     *
     * @return 是否成功撤销（false 表示已被并发事务先行变更状态）
     */
    public boolean markRevoked(String packageKey, String actorId, String reason, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, revoked_by = ?, revoked_at = ?, revoke_reason = ?, updated_at = ?
                        WHERE package_key = ? AND status = ?
                        """,
                DisclosurePackageStatus.REVOKED.name(), actorId, now, reason, now,
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
                    rs.getInt("revision"),
                    rs.getObject("as_of", LocalDateTime.class),
                    DisclosurePackageStatus.valueOf(rs.getString("status")),
                    rs.getString("operator_id"),
                    rs.getString("fingerprint"),
                    rs.getString("evidence_keys"),
                    rs.getInt("item_count"),
                    rs.getString("summary"),
                    rs.getString("delivered_by"),
                    rs.getObject("delivered_at", LocalDateTime.class),
                    rs.getString("revoked_by"),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getString("revoke_reason"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
