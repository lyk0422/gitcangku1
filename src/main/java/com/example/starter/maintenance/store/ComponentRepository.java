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
import com.example.starter.maintenance.domain.Installation;

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
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant readInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final RowMapper<Component> COMPONENT_MAPPER = (rs, rowNum) -> new Component(
            rs.getString("component_id"),
            rs.getLong("lifetime_minutes"),
            rs.getLong("used_minutes"),
            rs.getLong("version"));

    private static final RowMapper<Installation> INSTALLATION_MAPPER = (rs, rowNum) -> new Installation(
            rs.getLong("installation_id"),
            rs.getString("component_id"),
            rs.getString("equipment_id"),
            rs.getString("install_reading_id"),
            readInstant(rs, "install_sampled_at"),
            rs.getLong("install_cumulative_minutes"),
            rs.getString("remove_reading_id"),
            readInstant(rs, "remove_sampled_at"),
            (Long) rs.getObject("remove_cumulative_minutes"));

    private static final String INSTALLATION_COLUMNS =
            "installation_id, component_id, equipment_id, install_reading_id, install_sampled_at,"
                    + " install_cumulative_minutes, remove_reading_id, remove_sampled_at,"
                    + " remove_cumulative_minutes";

    // ---------- 部件 ----------

    public void insertComponent(String componentId, long lifetimeMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO component (component_id, lifetime_minutes, used_minutes, version, created_at)"
                        + " VALUES (?, ?, 0, 1, ?)",
                componentId, lifetimeMinutes, utc(createdAt));
    }

    public Optional<Component> findComponent(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifetime_minutes, used_minutes, version FROM component"
                        + " WHERE component_id = ?",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一部件的装拆写操作串行化，保证并发安装同一部件至多一台成功。 */
    public Optional<Component> findComponentForUpdate(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifetime_minutes, used_minutes, version FROM component"
                        + " WHERE component_id = ? FOR UPDATE",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String componentId) {
        jdbc.update("UPDATE component SET version = version + 1 WHERE component_id = ?", componentId);
    }

    /** 拆卸时把装拆区间差值计入部件累计寿命。 */
    public void addUsedMinutes(String componentId, long deltaMinutes) {
        jdbc.update("UPDATE component SET used_minutes = used_minutes + ? WHERE component_id = ?",
                deltaMinutes, componentId);
    }

    // ---------- 装拆账 ----------

    public long insertInstallation(String componentId, String equipmentId, String installReadingId,
                                   Instant installSampledAt, long installCumulativeMinutes,
                                   String installRequestId, Instant installedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO component_installation (component_id, equipment_id, install_reading_id,"
                            + " install_sampled_at, install_cumulative_minutes, install_request_id, installed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, componentId);
            ps.setString(2, equipmentId);
            ps.setString(3, installReadingId);
            ps.setObject(4, utc(installSampledAt));
            ps.setLong(5, installCumulativeMinutes);
            ps.setString(6, installRequestId);
            ps.setObject(7, utc(installedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("装拆记录主键生成失败");
        }
        return key.longValue();
    }

    /** 当前进行中的安装（未拆卸）；同一部件同一时刻至多一条。 */
    public Optional<Installation> findActiveInstallation(String componentId) {
        List<Installation> rows = jdbc.query(
                "SELECT " + INSTALLATION_COLUMNS + " FROM component_installation"
                        + " WHERE component_id = ? AND remove_reading_id IS NULL",
                INSTALLATION_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 最近一次已拆卸记录（按拆卸时刻最大者），用于新区间与历史区间重叠校验。 */
    public Optional<Installation> findLastRemoval(String componentId) {
        List<Installation> rows = jdbc.query(
                "SELECT " + INSTALLATION_COLUMNS + " FROM component_installation"
                        + " WHERE component_id = ? AND remove_reading_id IS NOT NULL"
                        + " ORDER BY remove_sampled_at DESC, installation_id DESC LIMIT 1",
                INSTALLATION_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    public void completeInstallation(long installationId, String removeReadingId, Instant removeSampledAt,
                                     long removeCumulativeMinutes, String removeRequestId, Instant removedAt) {
        jdbc.update("UPDATE component_installation SET remove_reading_id = ?, remove_sampled_at = ?,"
                        + " remove_cumulative_minutes = ?, remove_request_id = ?, removed_at = ?"
                        + " WHERE installation_id = ?",
                removeReadingId, utc(removeSampledAt), removeCumulativeMinutes, removeRequestId,
                utc(removedAt), installationId);
    }

    public List<Installation> listInstallations(String componentId) {
        return jdbc.query(
                "SELECT " + INSTALLATION_COLUMNS + " FROM component_installation"
                        + " WHERE component_id = ? ORDER BY install_sampled_at ASC, installation_id ASC",
                INSTALLATION_MAPPER, componentId);
    }

    /** 该读数是否已被任意装拆记录引用（安装或拆卸端点）；被引用的读数禁止修订。 */
    public boolean existsUsageOfReading(String equipmentId, String readingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM component_installation"
                        + " WHERE equipment_id = ? AND (install_reading_id = ? OR remove_reading_id = ?)",
                Integer.class, equipmentId, readingId, readingId);
        return count != null && count > 0;
    }
}
