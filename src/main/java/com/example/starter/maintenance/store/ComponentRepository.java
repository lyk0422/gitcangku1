package com.example.starter.maintenance.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.maintenance.domain.Component;
import com.example.starter.maintenance.domain.ComponentInstall;

/**
 * 部件装拆账数据访问层。所有 SQL 参数化；时刻字段（TIMESTAMP WITH TIME ZONE）以 UTC 存取。
 */
@Repository
public class ComponentRepository {

    private final JdbcTemplate jdbc;

    public ComponentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Component> COMPONENT_MAPPER = (rs, rowNum) -> new Component(
            rs.getString("component_id"),
            rs.getLong("lifetime_minutes"),
            rs.getLong("accumulated_minutes"),
            rs.getLong("version"));

    private static final RowMapper<ComponentInstall> INSTALL_MAPPER = (rs, rowNum) -> new ComponentInstall(
            rs.getLong("install_id"),
            rs.getString("component_id"),
            rs.getString("equipment_id"),
            rs.getString("install_reading_id"),
            readInstant(rs, "install_sampled_at"),
            rs.getLong("install_cumulative_minutes"),
            rs.getString("uninstall_reading_id"),
            readInstant(rs, "uninstall_sampled_at"),
            (Long) rs.getObject("uninstall_cumulative_minutes"),
            (Long) rs.getObject("duration_minutes"));

    // ---------- 部件 ----------

    public void insertComponent(String componentId, long lifetimeMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO component (component_id, lifetime_minutes, accumulated_minutes,"
                        + " version, created_at) VALUES (?, ?, 0, 1, ?)",
                componentId, lifetimeMinutes, utc(createdAt));
    }

    public Optional<Component> findComponent(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifetime_minutes, accumulated_minutes, version"
                        + " FROM component WHERE component_id = ?",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一部件的装拆写操作串行化，保证版本校验与归属变更原子。 */
    public Optional<Component> findComponentForUpdate(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifetime_minutes, accumulated_minutes, version"
                        + " FROM component WHERE component_id = ? FOR UPDATE",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 拆卸时累加寿命并推进版本。 */
    public void addAccumulatedAndIncrementVersion(String componentId, long durationMinutes) {
        jdbc.update("UPDATE component SET accumulated_minutes = accumulated_minutes + ?,"
                        + " version = version + 1 WHERE component_id = ?",
                durationMinutes, componentId);
    }

    /** 安装时仅推进版本（寿命在拆卸时计入）。 */
    public void incrementVersion(String componentId) {
        jdbc.update("UPDATE component SET version = version + 1 WHERE component_id = ?", componentId);
    }

    // ---------- 装拆账 ----------

    public long insertInstall(String componentId, String equipmentId, String installReadingId,
                              Instant installSampledAt, long installCumulativeMinutes,
                              String requestId, Instant installedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO component_install (component_id, equipment_id, install_reading_id,"
                            + " install_sampled_at, install_cumulative_minutes, install_request_id,"
                            + " installed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, componentId);
            ps.setString(2, equipmentId);
            ps.setString(3, installReadingId);
            ps.setObject(4, utc(installSampledAt));
            ps.setLong(5, installCumulativeMinutes);
            ps.setString(6, requestId);
            ps.setObject(7, utc(installedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("装拆记录主键生成失败");
        }
        return key.longValue();
    }

    public Optional<ComponentInstall> findInstall(long installId) {
        List<ComponentInstall> rows = jdbc.query(
                "SELECT install_id, component_id, equipment_id, install_reading_id, install_sampled_at,"
                        + " install_cumulative_minutes, uninstall_reading_id, uninstall_sampled_at,"
                        + " uninstall_cumulative_minutes, duration_minutes"
                        + " FROM component_install WHERE install_id = ?",
                INSTALL_MAPPER, installId);
        return rows.stream().findFirst();
    }

    /** 部件当前在装记录（至多一条，由 component_current 主键保证）。 */
    public Optional<ComponentInstall> findOpenInstall(String componentId) {
        List<ComponentInstall> rows = jdbc.query(
                "SELECT i.install_id, i.component_id, i.equipment_id, i.install_reading_id,"
                        + " i.install_sampled_at, i.install_cumulative_minutes, i.uninstall_reading_id,"
                        + " i.uninstall_sampled_at, i.uninstall_cumulative_minutes, i.duration_minutes"
                        + " FROM component_install i JOIN component_current c"
                        + " ON c.install_id = i.install_id WHERE c.component_id = ?",
                INSTALL_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    public void closeInstall(long installId, String uninstallReadingId, Instant uninstallSampledAt,
                             long uninstallCumulativeMinutes, long durationMinutes,
                             String requestId, Instant uninstalledAt) {
        jdbc.update("UPDATE component_install SET uninstall_reading_id = ?, uninstall_sampled_at = ?,"
                        + " uninstall_cumulative_minutes = ?, duration_minutes = ?,"
                        + " uninstall_request_id = ?, uninstalled_at = ? WHERE install_id = ?",
                uninstallReadingId, utc(uninstallSampledAt), uninstallCumulativeMinutes,
                durationMinutes, requestId, utc(uninstalledAt), installId);
    }

    public List<ComponentInstall> listInstalls(String componentId) {
        return jdbc.query(
                "SELECT install_id, component_id, equipment_id, install_reading_id, install_sampled_at,"
                        + " install_cumulative_minutes, uninstall_reading_id, uninstall_sampled_at,"
                        + " uninstall_cumulative_minutes, duration_minutes"
                        + " FROM component_install WHERE component_id = ?"
                        + " ORDER BY install_sampled_at ASC, install_id ASC",
                INSTALL_MAPPER, componentId);
    }

    /** 该读数是否已被任意装拆账引用（安装或拆卸锚点；被引用后禁止更正读数）。 */
    public boolean existsInstallReferencingReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_install WHERE equipment_id = ?"
                        + " AND (install_reading_id = ? OR uninstall_reading_id = ?)",
                Integer.class, equipmentId, readingId, readingId);
        return count != null && count > 0;
    }

    // ---------- 在装归属 ----------

    /** 登记在装归属；component_id 主键冲突即"同部件已装于其他设备"。 */
    public void insertCurrent(String componentId, long installId, String equipmentId,
                              Instant installedAt) {
        jdbc.update("INSERT INTO component_current (component_id, install_id, equipment_id,"
                        + " installed_at) VALUES (?, ?, ?, ?)",
                componentId, installId, equipmentId, utc(installedAt));
    }

    public void deleteCurrent(String componentId) {
        jdbc.update("DELETE FROM component_current WHERE component_id = ?", componentId);
    }
}
