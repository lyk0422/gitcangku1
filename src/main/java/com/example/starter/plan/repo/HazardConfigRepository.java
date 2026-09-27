package com.example.starter.plan.repo;

import com.example.starter.plan.model.HazardConfigVersion;
import com.example.starter.plan.model.HazardSectionOpen;
import com.example.starter.plan.model.HazardSeparationRule;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 危险品隔离配置版本持久化：版本主表、互斥类别间隔规则、限制区段与开放清单。
 * 所有时刻以 UTC 毫秒存储。
 */
@Repository
public class HazardConfigRepository {

    private final JdbcTemplate jdbc;

    public HazardConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入配置版本主记录，返回自增主键。
     */
    public long insertVersion(int versionNo, Instant effectiveFrom, Instant effectiveTo,
                              String fingerprint, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO hazard_config_version"
                            + " (version_no, effective_from_utc, effective_to_utc, fingerprint,"
                            + " created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setInt(1, versionNo);
            ps.setLong(2, effectiveFrom.toEpochMilli());
            if (effectiveTo == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, effectiveTo.toEpochMilli());
            }
            ps.setString(4, fingerprint);
            ps.setLong(5, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量插入互斥类别间隔规则。
     */
    public void insertSeparations(long configVersionId, List<HazardSeparationRule> rules) {
        if (rules.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO hazard_class_separation"
                        + " (config_version_id, class_a, class_b, min_cars) VALUES (?, ?, ?, ?)",
                rules, rules.size(),
                (ps, r) -> {
                    ps.setLong(1, configVersionId);
                    ps.setString(2, r.classA());
                    ps.setString(3, r.classB());
                    ps.setInt(4, r.minCars());
                });
    }

    /**
     * 批量插入限制区段登记。
     */
    public void insertRestrictedSections(long configVersionId, SortedSet<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return;
        }
        List<String> sections = new ArrayList<>(sectionIds);
        jdbc.batchUpdate(
                "INSERT INTO hazard_restricted_section (config_version_id, section_id)"
                        + " VALUES (?, ?)",
                sections, sections.size(),
                (ps, sectionId) -> {
                    ps.setLong(1, configVersionId);
                    ps.setString(2, sectionId);
                });
    }

    /**
     * 批量插入限制区段开放清单条目。
     */
    public void insertOpenClasses(long configVersionId, List<HazardSectionOpen> opens) {
        if (opens.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO hazard_section_open_class"
                        + " (config_version_id, section_id, hazard_class) VALUES (?, ?, ?)",
                opens, opens.size(),
                (ps, o) -> {
                    ps.setLong(1, configVersionId);
                    ps.setString(2, o.sectionId());
                    ps.setString(3, o.hazardClass());
                });
    }

    /**
     * 查询指定时刻（含）生效的配置版本，取生效开始时刻最晚者；无生效版本返回空。
     */
    public Optional<HazardConfigVersion> findEffectiveAt(Instant at) {
        long millis = at.toEpochMilli();
        List<HazardConfigVersion> versions = jdbc.query(
                "SELECT id, version_no, effective_from_utc, effective_to_utc, fingerprint, created_at"
                        + " FROM hazard_config_version"
                        + " WHERE effective_from_utc <= ?"
                        + " AND (effective_to_utc IS NULL OR effective_to_utc > ?)"
                        + " ORDER BY effective_from_utc DESC, version_no DESC LIMIT 1",
                VERSION_MAPPER, millis, millis);
        return versions.stream().findFirst();
    }

    /**
     * 查询与给定生效区间左闭右开重叠的配置版本（用于拒绝生效区间重叠）。
     */
    public List<HazardConfigVersion> findOverlapping(Instant from, Instant to) {
        long fromMillis = from.toEpochMilli();
        Long toMillis = to == null ? null : to.toEpochMilli();
        // 区间重叠（左闭右开）：a.from < b.to AND b.from < a.to，NULL 视为 +∞
        String sql = "SELECT id, version_no, effective_from_utc, effective_to_utc, fingerprint,"
                + " created_at FROM hazard_config_version"
                + " WHERE effective_from_utc < ?"
                + " AND (effective_to_utc IS NULL OR effective_to_utc > ?)";
        List<Object> args = new ArrayList<>();
        args.add(toMillis == null ? Long.MAX_VALUE : toMillis);
        args.add(fromMillis);
        if (toMillis != null) {
            // 新版本有限期时，长期有效的旧版本也会重叠，上面 SQL 已覆盖（其 to 为 NULL）
        }
        return jdbc.query(sql, VERSION_MAPPER, args.toArray());
    }

    /**
     * 查询配置版本的全部互斥类别间隔规则，按类别对升序。
     */
    public List<HazardSeparationRule> findSeparations(long configVersionId) {
        return jdbc.query("SELECT config_version_id, class_a, class_b, min_cars"
                        + " FROM hazard_class_separation WHERE config_version_id = ?"
                        + " ORDER BY class_a, class_b",
                (rs, n) -> new HazardSeparationRule(
                        rs.getLong("config_version_id"),
                        rs.getString("class_a"),
                        rs.getString("class_b"),
                        rs.getInt("min_cars")),
                configVersionId);
    }

    /**
     * 查询配置版本下全部已登记的限制区段（含开放清单为空者），按区段升序。
     */
    public SortedSet<String> findRestrictedSections(long configVersionId) {
        return new TreeSet<>(jdbc.query("SELECT section_id FROM hazard_restricted_section"
                + " WHERE config_version_id = ? ORDER BY section_id",
                (rs, n) -> rs.getString("section_id"), configVersionId));
    }

    /**
     * 查询配置版本下全部开放清单条目。
     */
    public List<HazardSectionOpen> findOpenClasses(long configVersionId) {
        return jdbc.query("SELECT config_version_id, section_id, hazard_class"
                        + " FROM hazard_section_open_class WHERE config_version_id = ?"
                        + " ORDER BY section_id, hazard_class",
                (rs, n) -> new HazardSectionOpen(
                        rs.getLong("config_version_id"),
                        rs.getString("section_id"),
                        rs.getString("hazard_class")),
                configVersionId);
    }

    /**
     * 查询配置版本的开放清单，按区段分组（区段 -> 开放类别集合）。
     */
    public TreeMap<String, SortedSet<String>> findOpenClassMap(long configVersionId) {
        TreeMap<String, SortedSet<String>> map = new TreeMap<>();
        for (HazardSectionOpen open : findOpenClasses(configVersionId)) {
            map.computeIfAbsent(open.sectionId(), k -> new TreeSet<>()).add(open.hazardClass());
        }
        return map;
    }

    /**
     * 按版本号查询配置版本主记录。
     */
    public Optional<HazardConfigVersion> findByVersionNo(int versionNo) {
        return jdbc.query("SELECT id, version_no, effective_from_utc, effective_to_utc, fingerprint,"
                        + " created_at FROM hazard_config_version WHERE version_no = ?",
                VERSION_MAPPER, versionNo).stream().findFirst();
    }

    /**
     * 按主键查询配置版本主记录。
     */
    public Optional<HazardConfigVersion> findById(long id) {
        return jdbc.query("SELECT id, version_no, effective_from_utc, effective_to_utc, fingerprint,"
                        + " created_at FROM hazard_config_version WHERE id = ?",
                VERSION_MAPPER, id).stream().findFirst();
    }

    /**
     * 当前最大版本号，无记录返回 0。
     */
    public int findMaxVersionNo() {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version_no) FROM hazard_config_version", Integer.class);
        return max == null ? 0 : max;
    }

    private static final org.springframework.jdbc.core.RowMapper<HazardConfigVersion> VERSION_MAPPER =
            (rs, n) -> {
                Long to = rs.getObject("effective_to_utc", Long.class);
                return new HazardConfigVersion(
                        rs.getLong("id"),
                        rs.getInt("version_no"),
                        Instant.ofEpochMilli(rs.getLong("effective_from_utc")),
                        to == null ? null : Instant.ofEpochMilli(to),
                        rs.getString("fingerprint"),
                        rs.getLong("created_at"));
            };
}
