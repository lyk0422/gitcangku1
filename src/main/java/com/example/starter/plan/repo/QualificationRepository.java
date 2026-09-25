package com.example.starter.plan.repo;

import com.example.starter.plan.model.Qualification;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 乘务员资质与覆盖区段的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 */
@Repository
public class QualificationRepository {

    private static final RowMapper<Qualification> QUAL_MAPPER = (rs, n) -> new Qualification(
            rs.getLong("id"),
            rs.getString("crew_id"),
            rs.getString("qualification_code"),
            Instant.ofEpochMilli(rs.getLong("expires_at_utc")),
            rs.getBoolean("terminated"),
            rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public QualificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入资质主记录，返回自增主键。
     */
    public long insert(String crewId, String qualificationCode,
                       Instant expiresAtUtc, int version, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_crew_qualification"
                            + " (crew_id, qualification_code, expires_at_utc, terminated,"
                            + " version, created_at, updated_at) VALUES (?, ?, ?, FALSE, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, crewId);
            ps.setString(2, qualificationCode);
            ps.setLong(3, expiresAtUtc.toEpochMilli());
            ps.setInt(4, version);
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 整体替换资质覆盖区段集合：先删后插。
     */
    public void replaceSections(String crewId, String qualificationCode, List<String> sectionIds) {
        jdbc.update("DELETE FROM rail_crew_qualification_section WHERE crew_id = ?"
                + " AND qualification_code = ?", crewId, qualificationCode);
        insertSections(crewId, qualificationCode, sectionIds);
    }

    /**
     * 批量插入资质覆盖区段。
     */
    public void insertSections(String crewId, String qualificationCode, List<String> sectionIds) {
        jdbc.batchUpdate(
                "INSERT INTO rail_crew_qualification_section (crew_id, qualification_code, section_id)"
                        + " VALUES (?, ?, ?)",
                sectionIds, sectionIds.size(),
                (ps, sectionId) -> {
                    ps.setString(1, crewId);
                    ps.setString(2, qualificationCode);
                    ps.setString(3, sectionId);
                });
    }

    /**
     * 按乘务员与资质代码查询资质（不加锁）。
     */
    public Optional<Qualification> find(String crewId, String qualificationCode) {
        return jdbc.query("SELECT id, crew_id, qualification_code, expires_at_utc,"
                        + " terminated, version FROM rail_crew_qualification"
                        + " WHERE crew_id = ? AND qualification_code = ?",
                QUAL_MAPPER, crewId, qualificationCode).stream().findFirst();
    }

    /**
     * 按乘务员与资质代码查询资质并加行级写锁，须在事务内调用。
     */
    public Optional<Qualification> findForUpdate(String crewId, String qualificationCode) {
        return jdbc.query("SELECT id, crew_id, qualification_code, expires_at_utc,"
                        + " terminated, version FROM rail_crew_qualification"
                        + " WHERE crew_id = ? AND qualification_code = ? FOR UPDATE",
                QUAL_MAPPER, crewId, qualificationCode).stream().findFirst();
    }

    /**
     * 修改资质：更新到期时刻与版本号；覆盖区段由调用方先删后插。
     */
    public void updateQualification(String crewId, String qualificationCode,
                                    Instant expiresAtUtc, int version, long nowMillis) {
        jdbc.update("UPDATE rail_crew_qualification SET expires_at_utc = ?,"
                        + " version = ?, updated_at = ? WHERE crew_id = ? AND qualification_code = ?",
                expiresAtUtc.toEpochMilli(), version, nowMillis,
                crewId, qualificationCode);
    }

    /**
     * 提前终止资质：置终止标志并递增版本。
     */
    public void markTerminated(String crewId, String qualificationCode, int version,
                               long nowMillis) {
        jdbc.update("UPDATE rail_crew_qualification SET terminated = TRUE, version = ?,"
                        + " updated_at = ? WHERE crew_id = ? AND qualification_code = ?",
                version, nowMillis, crewId, qualificationCode);
    }

    /**
     * 查询乘务员全部资质（含已终止），按资质代码升序。
     */
    public List<Qualification> findByCrewId(String crewId) {
        return jdbc.query("SELECT id, crew_id, qualification_code, expires_at_utc,"
                        + " terminated, version FROM rail_crew_qualification"
                        + " WHERE crew_id = ? ORDER BY qualification_code",
                QUAL_MAPPER, crewId);
    }

    /**
     * 查询资质覆盖区段，按区段 ID 升序（集合规范化顺序）。
     */
    public List<String> findSections(String crewId, String qualificationCode) {
        return jdbc.queryForList("SELECT section_id FROM rail_crew_qualification_section"
                        + " WHERE crew_id = ? AND qualification_code = ? ORDER BY section_id",
                String.class, crewId, qualificationCode);
    }
}
