package com.example.starter.plan.repo;

import com.example.starter.plan.model.DisruptionMapping;
import com.example.starter.plan.model.DisruptionReplaceLink;
import com.example.starter.plan.model.DisruptionStatus;
import com.example.starter.plan.model.DisruptionSwitch;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 区段封锁切换单、映射、替代链与快照的 JDBC 持久化。窗口时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class DisruptionRepository {

    private static final RowMapper<DisruptionSwitch> SWITCH_MAPPER = (rs, n) -> new DisruptionSwitch(
            rs.getLong("id"),
            rs.getString("switch_key"),
            rs.getString("section_id"),
            rs.getLong("window_start_utc"),
            rs.getLong("window_end_utc"),
            DisruptionStatus.valueOf(rs.getString("status")),
            rs.getString("activated_request_id"));

    private static final RowMapper<DisruptionMapping> MAPPING_MAPPER = (rs, n) ->
            new DisruptionMapping(
                    rs.getLong("id"),
                    rs.getLong("switch_id"),
                    rs.getLong("old_plan_id"),
                    rs.getLong("replacement_plan_id"),
                    rs.getInt("expected_old_version"));

    private static final RowMapper<DisruptionReplaceLink> LINK_MAPPER = (rs, n) ->
            new DisruptionReplaceLink(
                    rs.getLong("id"),
                    rs.getLong("switch_id"),
                    rs.getLong("suspended_plan_id"),
                    rs.getLong("replacement_plan_id"));

    private final JdbcTemplate jdbc;

    public DisruptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入切换单（REGISTERED），返回自增主键；switchKey 唯一冲突时抛出 DuplicateKeyException。
     */
    public long insertSwitch(String switchKey, String sectionId, long windowStartUtc,
                             long windowEndUtc, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_disruption_switch"
                            + " (switch_key, section_id, window_start_utc, window_end_utc, status,"
                            + " created_at, updated_at) VALUES (?, ?, ?, ?, 'REGISTERED', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, switchKey);
            ps.setString(2, sectionId);
            ps.setLong(3, windowStartUtc);
            ps.setLong(4, windowEndUtc);
            ps.setLong(5, nowMillis);
            ps.setLong(6, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询切换单（不加锁）。
     */
    public Optional<DisruptionSwitch> findSwitchByKey(String switchKey) {
        return jdbc.query("SELECT id, switch_key, section_id, window_start_utc, window_end_utc, status,"
                        + " activated_request_id FROM rail_disruption_switch WHERE switch_key = ?",
                SWITCH_MAPPER, switchKey).stream().findFirst();
    }

    /**
     * 按业务键查询切换单并加行级写锁，须在事务内调用。
     */
    public Optional<DisruptionSwitch> findSwitchByKeyForUpdate(String switchKey) {
        return jdbc.query("SELECT id, switch_key, section_id, window_start_utc, window_end_utc, status,"
                        + " activated_request_id FROM rail_disruption_switch WHERE switch_key = ? FOR UPDATE",
                SWITCH_MAPPER, switchKey).stream().findFirst();
    }

    /**
     * 激活成功：切换单置 ACTIVE 并记录成功激活的 requestId。
     */
    public void markActive(long switchId, String requestId, long nowMillis) {
        jdbc.update("UPDATE rail_disruption_switch SET status = 'ACTIVE', activated_request_id = ?,"
                        + " updated_at = ? WHERE id = ?",
                requestId, nowMillis, switchId);
    }

    /**
     * 删除切换单既有映射（重新提交时整体替换，提交失败不改变任何状态）。
     */
    public void deleteMappings(long switchId) {
        jdbc.update("DELETE FROM rail_disruption_mapping WHERE switch_id = ?", switchId);
    }

    /**
     * 插入一条旧计划到替代草稿计划的映射。
     */
    public void insertMapping(long switchId, long oldPlanId, long replacementPlanId,
                              int expectedOldVersion, long nowMillis) {
        jdbc.update("INSERT INTO rail_disruption_mapping"
                        + " (switch_id, old_plan_id, replacement_plan_id, expected_old_version, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                switchId, oldPlanId, replacementPlanId, expectedOldVersion, nowMillis);
    }

    /**
     * 查询切换单的全部提交映射，按旧计划 id 升序保证顺序稳定。
     */
    public List<DisruptionMapping> findMappings(long switchId) {
        return jdbc.query("SELECT id, switch_id, old_plan_id, replacement_plan_id, expected_old_version"
                        + " FROM rail_disruption_mapping WHERE switch_id = ? ORDER BY old_plan_id",
                MAPPING_MAPPER, switchId);
    }

    /**
     * 写入不可变一对一替代链；旧计划或替代计划全表唯一冲突时抛出 DuplicateKeyException。
     */
    public void insertReplaceLink(long switchId, long suspendedPlanId, long replacementPlanId,
                                  long nowMillis) {
        jdbc.update("INSERT INTO rail_disruption_replace_link"
                        + " (switch_id, suspended_plan_id, replacement_plan_id, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                switchId, suspendedPlanId, replacementPlanId, nowMillis);
    }

    /**
     * 查询以指定计划为被挂起方的替代链。
     */
    public Optional<DisruptionReplaceLink> findReplaceLinkBySuspended(long suspendedPlanId) {
        return jdbc.query("SELECT id, switch_id, suspended_plan_id, replacement_plan_id"
                        + " FROM rail_disruption_replace_link WHERE suspended_plan_id = ?",
                LINK_MAPPER, suspendedPlanId).stream().findFirst();
    }

    /**
     * 查询以指定计划为替代方的替代链。
     */
    public Optional<DisruptionReplaceLink> findReplaceLinkByReplacement(long replacementPlanId) {
        return jdbc.query("SELECT id, switch_id, suspended_plan_id, replacement_plan_id"
                        + " FROM rail_disruption_replace_link WHERE replacement_plan_id = ?",
                LINK_MAPPER, replacementPlanId).stream().findFirst();
    }

    /**
     * 写入激活完整快照；同一切换单唯一，重复激活插入时抛 DuplicateKeyException。
     */
    public void insertSnapshot(long switchId, String snapshotJson, long nowMillis) {
        jdbc.update("INSERT INTO rail_disruption_snapshot (switch_id, snapshot_json, created_at)"
                        + " VALUES (?, ?, ?)",
                switchId, snapshotJson, nowMillis);
    }

    /**
     * 查询切换单的激活快照 JSON，未激活时为空。
     */
    public Optional<String> findSnapshot(long switchId) {
        return jdbc.query("SELECT snapshot_json FROM rail_disruption_snapshot WHERE switch_id = ?",
                (rs, n) -> rs.getString("snapshot_json"), switchId).stream().findFirst();
    }
}
