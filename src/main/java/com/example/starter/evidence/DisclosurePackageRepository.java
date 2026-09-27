package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 披露包版本表访问。版本行只追加、不可变：
 * 新版本不覆盖旧版本，交付/撤销仅在版本行上条件更新状态与留痕字段。
 */
@Repository
public class DisclosurePackageRepository {

    private static final PackageRowMapper ROW_MAPPER = new PackageRowMapper();

    private final JdbcTemplate jdbc;

    public DisclosurePackageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个新版本行（GENERATED），返回生成的自增主键。
     */
    public long insert(String caseKey, int version, LocalDateTime asOfUtc, String packageKey,
                       String actorId, String frozenSummary, int itemCount, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO disclosure_package
                                (case_key, package_version, as_of_utc, status, package_key, actor_id,
                                 frozen_summary, item_count, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, caseKey);
            ps.setInt(2, version);
            ps.setObject(3, asOfUtc);
            ps.setString(4, PackageStatus.GENERATED.name());
            ps.setString(5, packageKey);
            ps.setString(6, actorId);
            ps.setString(7, frozenSummary);
            ps.setInt(8, itemCount);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按案件查询最新版本（版本号最大）；无包时返回空。
     */
    public Optional<DisclosurePackage> findLatestByCase(String caseKey) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE case_key = ? ORDER BY package_version DESC LIMIT 1",
                ROW_MAPPER, caseKey);
        return rows.stream().findFirst();
    }

    /**
     * 按案件与版本号查询版本行。
     */
    public Optional<DisclosurePackage> findByCaseAndVersion(String caseKey, int version) {
        List<DisclosurePackage> rows = jdbc.query(
                "SELECT * FROM disclosure_package WHERE case_key = ? AND package_version = ?",
                ROW_MAPPER, caseKey, version);
        return rows.stream().findFirst();
    }

    /**
     * 条件交付：仅当版本仍为 GENERATED 时写入交付留痕并置为 DELIVERED。
     *
     * @return 是否成功（false 表示已被并发处理或状态不符）
     */
    public boolean deliver(long id, String reason, String actorId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, deliver_reason = ?, delivered_by = ?, delivered_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageStatus.DELIVERED.name(), reason, actorId, now,
                id, PackageStatus.GENERATED.name());
        return updated == 1;
    }

    /**
     * 条件撤销：仅当版本仍为 GENERATED 时写入撤销原因并置为 REVOKED。
     *
     * @return 是否成功（false 表示已被并发处理或状态不符）
     */
    public boolean revoke(long id, String reason, String actorId, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE disclosure_package
                        SET status = ?, revoke_reason = ?, revoked_by = ?, revoked_at = ?
                        WHERE id = ? AND status = ?
                        """,
                PackageStatus.REVOKED.name(), reason, actorId, now,
                id, PackageStatus.GENERATED.name());
        return updated == 1;
    }

    /**
     * 按案件查询全部版本（按版本号升序），用于版本历史查询。
     */
    public List<DisclosurePackage> findAllByCase(String caseKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package WHERE case_key = ? ORDER BY package_version",
                ROW_MAPPER, caseKey);
    }

    private static final class PackageRowMapper implements RowMapper<DisclosurePackage> {
        @Override
        public DisclosurePackage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DisclosurePackage(
                    rs.getLong("id"),
                    rs.getString("case_key"),
                    rs.getInt("package_version"),
                    rs.getObject("as_of_utc", LocalDateTime.class),
                    PackageStatus.valueOf(rs.getString("status")),
                    rs.getString("package_key"),
                    rs.getString("actor_id"),
                    rs.getString("frozen_summary"),
                    rs.getInt("item_count"),
                    rs.getString("deliver_reason"),
                    rs.getString("delivered_by"),
                    rs.getObject("delivered_at", LocalDateTime.class),
                    rs.getString("revoke_reason"),
                    rs.getString("revoked_by"),
                    rs.getObject("revoked_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
