package com.example.starter.plan.repo;

import com.example.starter.plan.model.PowerSlot;
import com.example.starter.plan.model.PowerSnapshot;
import com.example.starter.plan.model.SectionBinding;
import com.example.starter.plan.model.Substation;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * 变电所、区段绑定与功率快照的 JDBC 持久化。时刻均为 UTC 毫秒，功率为整数千瓦。
 */
@Repository
public class SubstationRepository {

    private static final RowMapper<Substation> SUBSTATION_MAPPER = (rs, n) -> new Substation(
            rs.getLong("id"),
            rs.getString("substation_key"),
            rs.getInt("power_limit_kw"),
            rs.getInt("version"));

    private static final RowMapper<SectionBinding> BINDING_MAPPER = (rs, n) -> new SectionBinding(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getLong("substation_id"));

    private static final RowMapper<PowerSnapshot> SNAPSHOT_MAPPER = (rs, n) -> new PowerSnapshot(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            rs.getString("section_id"),
            rs.getLong("substation_id"),
            rs.getString("substation_key"),
            rs.getInt("limit_version"),
            rs.getInt("limit_kw"),
            rs.getInt("power_kw"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private final JdbcTemplate jdbc;

    public SubstationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入变电所（版本 1），返回自增主键；业务键冲突时抛出 DuplicateKeyException。
     */
    public long insertSubstation(String substationKey, int powerLimitKw, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_substation (substation_key, power_limit_kw, version,"
                            + " created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, substationKey);
            ps.setInt(2, powerLimitKw);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询变电所（不加锁）。
     */
    public Optional<Substation> findByKey(String substationKey) {
        return jdbc.query("SELECT id, substation_key, power_limit_kw, version FROM rail_substation"
                        + " WHERE substation_key = ?",
                SUBSTATION_MAPPER, substationKey).stream().findFirst();
    }

    /**
     * 按业务键查询变电所并加行级写锁，须在事务内调用。
     */
    public Optional<Substation> findByKeyForUpdate(String substationKey) {
        return jdbc.query("SELECT id, substation_key, power_limit_kw, version FROM rail_substation"
                        + " WHERE substation_key = ? FOR UPDATE",
                SUBSTATION_MAPPER, substationKey).stream().findFirst();
    }

    /**
     * 按主键查询变电所（不加锁）。
     */
    public Optional<Substation> findById(long id) {
        return jdbc.query("SELECT id, substation_key, power_limit_kw, version FROM rail_substation"
                        + " WHERE id = ?",
                SUBSTATION_MAPPER, id).stream().findFirst();
    }

    /**
     * 调整功率上限并递增版本。
     */
    public void updateLimit(long id, int powerLimitKw, int version, long nowMillis) {
        jdbc.update("UPDATE rail_substation SET power_limit_kw = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                powerLimitKw, version, nowMillis, id);
    }

    /**
     * 查询区段当前绑定（不加锁）。
     */
    public Optional<SectionBinding> findBinding(String sectionId) {
        return jdbc.query("SELECT id, section_id, substation_id FROM rail_section_binding"
                        + " WHERE section_id = ?",
                BINDING_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 查询区段当前绑定并加行级写锁，须在事务内调用。
     */
    public Optional<SectionBinding> findBindingForUpdate(String sectionId) {
        return jdbc.query("SELECT id, section_id, substation_id FROM rail_section_binding"
                        + " WHERE section_id = ? FOR UPDATE",
                BINDING_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 首次绑定区段。
     */
    public void insertBinding(String sectionId, long substationId, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_binding (section_id, substation_id, created_at,"
                        + " updated_at) VALUES (?, ?, ?, ?)",
                sectionId, substationId, nowMillis, nowMillis);
    }

    /**
     * 改绑到另一变电所。
     */
    public void updateBinding(long bindingId, long substationId, long nowMillis) {
        jdbc.update("UPDATE rail_section_binding SET substation_id = ?, updated_at = ?"
                        + " WHERE id = ?",
                substationId, nowMillis, bindingId);
    }

    /**
     * 查询绑定到指定变电所的全部区段 ID。
     */
    public List<String> findSectionsBySubstation(long substationId) {
        return jdbc.query("SELECT section_id FROM rail_section_binding WHERE substation_id = ?"
                        + " ORDER BY section_id",
                (rs, n) -> rs.getString("section_id"), substationId);
    }

    /**
     * 查询指定运营日、指定区段集合上已发布计划的功率时隙（排除给定计划集合）。
     */
    public List<PowerSlot> findPublishedPowerSlots(LocalDate opDate, Collection<String> sectionIds,
                                                   Collection<Long> excludePlanIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT o.section_id, o.start_utc, o.end_utc, o.power_kw"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND p.op_date = ?");
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(opDate));
        appendSectionFilter(sql, args, sectionIds, excludePlanIds);
        return queryPowerSlots(sql, args);
    }

    /**
     * 查询指定区段集合上全部已发布计划的功率时隙（不限运营日，用于调上限/改绑裁决）。
     */
    public List<PowerSlot> findPublishedPowerSlotsBySections(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT o.section_id, o.start_utc, o.end_utc, o.power_kw"
                        + " FROM rail_plan_occupancy o JOIN rail_day_plan p ON p.id = o.plan_id"
                        + " WHERE p.status = 'PUBLISHED'");
        List<Object> args = new ArrayList<>();
        appendSectionFilter(sql, args, sectionIds, List.of());
        return queryPowerSlots(sql, args);
    }

    private void appendSectionFilter(StringBuilder sql, List<Object> args,
                                     Collection<String> sectionIds, Collection<Long> excludePlanIds) {
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND p.id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        StringJoiner sectionPlaceholders = new StringJoiner(", ");
        sectionIds.forEach(s -> sectionPlaceholders.add("?"));
        sql.append(" AND o.section_id IN (").append(sectionPlaceholders)
                .append(") ORDER BY o.section_id, o.start_utc");
        args.addAll(sectionIds);
    }

    private List<PowerSlot> queryPowerSlots(StringBuilder sql, List<Object> args) {
        return jdbc.query(sql.toString(), (rs, n) -> new PowerSlot(
                rs.getString("section_id"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc")),
                rs.getInt("power_kw")), args.toArray());
    }

    /**
     * 批量写入发布/改签裁决时冻结的功率快照。
     */
    public void insertPowerSnapshots(List<PowerSnapshot> snapshots, long nowMillis) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_power_snapshot (plan_id, seq, section_id, substation_id,"
                        + " substation_key, limit_version, limit_kw, power_kw, start_utc, end_utc,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshots, snapshots.size(),
                (ps, s) -> {
                    ps.setLong(1, s.planId());
                    ps.setInt(2, s.seq());
                    ps.setString(3, s.sectionId());
                    ps.setLong(4, s.substationId());
                    ps.setString(5, s.substationKey());
                    ps.setInt(6, s.limitVersion());
                    ps.setInt(7, s.limitKw());
                    ps.setInt(8, s.powerKw());
                    ps.setLong(9, s.startUtc().toEpochMilli());
                    ps.setLong(10, s.endUtc().toEpochMilli());
                    ps.setLong(11, nowMillis);
                });
    }

    /**
     * 查询计划的冻结功率快照，按计划内序号升序。
     */
    public List<PowerSnapshot> findSnapshots(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, section_id, substation_id, substation_key,"
                        + " limit_version, limit_kw, power_kw, start_utc, end_utc"
                        + " FROM rail_plan_power_snapshot"
                        + " WHERE plan_id = ? ORDER BY seq",
                SNAPSHOT_MAPPER, planId);
    }
}
