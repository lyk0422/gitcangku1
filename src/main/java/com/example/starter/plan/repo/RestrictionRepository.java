package com.example.starter.plan.repo;

import com.example.starter.plan.model.RestrictionStatus;
import com.example.starter.plan.model.SpeedRestriction;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 气象限速令的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class RestrictionRepository {

    private static final RowMapper<SpeedRestriction> MAPPER = (rs, n) -> new SpeedRestriction(
            rs.getLong("id"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            rs.getInt("max_speed_kmh"),
            RestrictionStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getString("operator"),
            Instant.ofEpochMilli(rs.getLong("created_at")),
            rs.getObject("revoked_at") == null ? null : Instant.ofEpochMilli(rs.getLong("revoked_at")));

    private static final String COLUMNS = "id, section_id, start_utc, end_utc, max_speed_kmh,"
            + " status, version, operator, created_at, revoked_at";

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记限速令（版本恒为 1，状态 ACTIVE），返回自增主键。
     */
    public long insert(String sectionId, Instant startUtc, Instant endUtc, int maxSpeedKmh,
                       String operator, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_speed_restriction"
                            + " (section_id, start_utc, end_utc, max_speed_kmh, status, version,"
                            + " operator, created_at, revoked_at)"
                            + " VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, sectionId);
            ps.setLong(2, startUtc.toEpochMilli());
            ps.setLong(3, endUtc.toEpochMilli());
            ps.setInt(4, maxSpeedKmh);
            ps.setString(5, operator);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按主键查询（不加锁）。
     */
    public Optional<SpeedRestriction> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction WHERE id = ?",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按主键查询并加行级写锁，须在事务内调用，用于串行化撤销。
     */
    public Optional<SpeedRestriction> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction WHERE id = ? FOR UPDATE",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 查询指定区段集合上当前生效（ACTIVE）的限速令，按区段、开始时刻、id 升序，排序稳定。
     */
    public List<SpeedRestriction> findActiveBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT " + COLUMNS + " FROM rail_speed_restriction"
                        + " WHERE status = 'ACTIVE' AND section_id IN (" + placeholders + ")"
                        + " ORDER BY section_id, start_utc, id",
                MAPPER, sectionIds.toArray());
    }

    /**
     * 明细查询：按区段（可空表示全部）与是否包含已撤销过滤，按区段、开始时刻、id 升序。
     */
    public List<SpeedRestriction> findAll(String sectionId, boolean includeRevoked) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + COLUMNS + " FROM rail_speed_restriction WHERE 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (sectionId != null) {
            sql.append(" AND section_id = ?");
            args.add(sectionId);
        }
        if (!includeRevoked) {
            sql.append(" AND status = 'ACTIVE'");
        }
        sql.append(" ORDER BY section_id, start_utc, id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /**
     * 撤销限速令：状态置为 REVOKED 并记录撤销时刻；历史行与已生成重排记录不改写。
     */
    public void markRevoked(long id, long nowMillis) {
        jdbc.update("UPDATE rail_speed_restriction SET status = 'REVOKED', revoked_at = ?"
                + " WHERE id = ?", nowMillis, id);
    }

    /**
     * 限速令全集版本令牌：任一登记或撤销都会改变该值，用于发布/改签幂等指纹。
     */
    public String versionToken() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) || '-' || COALESCE(MAX(id), 0) || '-'"
                        + " || COALESCE(SUM(CASE WHEN status = 'REVOKED' THEN 1 ELSE 0 END), 0)"
                        + " FROM rail_speed_restriction",
                String.class);
    }
}
