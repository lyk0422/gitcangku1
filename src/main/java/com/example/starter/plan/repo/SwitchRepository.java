package com.example.starter.plan.repo;

import com.example.starter.plan.model.ReplacementLink;
import com.example.starter.plan.model.SectionSwitch;
import com.example.starter.plan.model.SwitchStatus;
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
 * 区段封锁切换单与一对一替代关联的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 */
@Repository
public class SwitchRepository {

    private static final RowMapper<SectionSwitch> SWITCH_MAPPER = (rs, n) -> new SectionSwitch(
            rs.getLong("id"),
            rs.getString("switch_key"),
            rs.getString("section_id"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")),
            SwitchStatus.valueOf(rs.getString("status")),
            rs.getString("snapshot_json"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final RowMapper<ReplacementLink> LINK_MAPPER = (rs, n) -> new ReplacementLink(
            rs.getLong("id"),
            rs.getLong("switch_id"),
            rs.getLong("old_plan_id"),
            rs.getLong("replacement_plan_id"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public SwitchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记切换单（REGISTERED），返回自增主键；switchKey 唯一冲突抛 DuplicateKeyException。
     */
    public long insertSwitch(String switchKey, String sectionId, Instant startUtc, Instant endUtc,
                             long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_section_switch"
                            + " (switch_key, section_id, start_utc, end_utc, status,"
                            + " snapshot_json, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 'REGISTERED', NULL, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, switchKey);
            ps.setString(2, sectionId);
            ps.setLong(3, startUtc.toEpochMilli());
            ps.setLong(4, endUtc.toEpochMilli());
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询切换单（不加锁）。
     */
    public Optional<SectionSwitch> findByKey(String switchKey) {
        return jdbc.query("SELECT id, switch_key, section_id, start_utc, end_utc, status,"
                        + " snapshot_json, created_at, updated_at"
                        + " FROM rail_section_switch WHERE switch_key = ?",
                SWITCH_MAPPER, switchKey).stream().findFirst();
    }

    /**
     * 按业务键查询切换单并加行级写锁，须在事务内调用，串行化同一切换单的并发激活。
     */
    public Optional<SectionSwitch> findByKeyForUpdate(String switchKey) {
        return jdbc.query("SELECT id, switch_key, section_id, start_utc, end_utc, status,"
                        + " snapshot_json, created_at, updated_at"
                        + " FROM rail_section_switch WHERE switch_key = ? FOR UPDATE",
                SWITCH_MAPPER, switchKey).stream().findFirst();
    }

    /**
     * 激活提交：切换单置为 ACTIVE 并写入不可变完整快照。
     */
    public void markActive(long switchId, String snapshotJson, long nowMillis) {
        jdbc.update("UPDATE rail_section_switch SET status = 'ACTIVE', snapshot_json = ?,"
                        + " updated_at = ? WHERE id = ?",
                snapshotJson, nowMillis, switchId);
    }

    /**
     * 查询当前与封锁窗口（同区段、左闭右开 UTC）相交的全部 PUBLISHED 计划 id，按 id 升序。
     * 相交判定（左闭右开，端点相接不相交）：占用 start &lt; 窗口 end 且占用 end &gt; 窗口 start。
     */
    public List<Long> findPublishedPlanIdsIntersecting(String sectionId, Instant startUtc,
                                                       Instant endUtc) {
        return jdbc.queryForList(
                "SELECT DISTINCT p.id FROM rail_day_plan p"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED' AND o.section_id = ?"
                        + " AND o.start_utc < ? AND o.end_utc > ? ORDER BY p.id",
                Long.class, sectionId, endUtc.toEpochMilli(), startUtc.toEpochMilli());
    }

    /**
     * 批量写入一对一替代关联（不可变）。每个元素为 [oldPlanId, replacementPlanId]。
     */
    public void insertReplacementLinks(long switchId, List<long[]> pairs, long nowMillis) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_replacement_link"
                        + " (switch_id, old_plan_id, replacement_plan_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                pairs, pairs.size(),
                (ps, pair) -> {
                    ps.setLong(1, switchId);
                    ps.setLong(2, pair[0]);
                    ps.setLong(3, pair[1]);
                    ps.setLong(4, nowMillis);
                });
    }

    /**
     * 查询以指定计划为被替代旧计划的关联（该计划是否已被某次切换挂起替代）。
     */
    public Optional<ReplacementLink> findReplacementByOldPlan(long oldPlanId) {
        return jdbc.query("SELECT id, switch_id, old_plan_id, replacement_plan_id, created_at"
                        + " FROM rail_plan_replacement_link WHERE old_plan_id = ?",
                LINK_MAPPER, oldPlanId).stream().findFirst();
    }

    /**
     * 查询以指定计划为替代计划的关联（该计划是否已在某次切换中替代过旧计划）。
     */
    public Optional<ReplacementLink> findReplacementByReplacementPlan(long replacementPlanId) {
        return jdbc.query("SELECT id, switch_id, old_plan_id, replacement_plan_id, created_at"
                        + " FROM rail_plan_replacement_link WHERE replacement_plan_id = ?",
                LINK_MAPPER, replacementPlanId).stream().findFirst();
    }
}
