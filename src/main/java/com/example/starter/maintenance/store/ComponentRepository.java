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
            rs.getLong("lifespan_minutes"),
            rs.getLong("accumulated_minutes"),
            rs.getLong("version"));

    private static final RowMapper<ComponentInstall> INSTALL_MAPPER = (rs, rowNum) -> new ComponentInstall(
            rs.getLong("install_id"),
            rs.getString("component_id"),
            rs.getString("equipment_id"),
            rs.getString("install_reading_id"),
            readInstant(rs, "install_sampled_at"),
            rs.getLong("install_cumulative_minutes"),
            rs.getString("remove_reading_id"),
            readInstant(rs, "remove_sampled_at"),
            rs.getObject("remove_cumulative_minutes", Long.class),
            rs.getString("install_operator"),
            rs.getString("remove_operator"),
            readInstant(rs, "installed_at"),
            readInstant(rs, "removed_at"));

    private static final String INSTALL_COLUMNS =
            "install_id, component_id, equipment_id, install_reading_id, install_sampled_at,"
                    + " install_cumulative_minutes, remove_reading_id, remove_sampled_at,"
                    + " remove_cumulative_minutes, install_operator, remove_operator,"
                    + " installed_at, removed_at";

    // ---------- 部件 ----------

    public void insertComponent(String componentId, long lifespanMinutes, Instant createdAt) {
        jdbc.update("INSERT INTO component (component_id, lifespan_minutes, accumulated_minutes,"
                        + " version, created_at) VALUES (?, ?, 0, 1, ?)",
                componentId, lifespanMinutes, utc(createdAt));
    }

    public Optional<Component> findComponent(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifespan_minutes, accumulated_minutes, version"
                        + " FROM component WHERE component_id = ?",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 悲观行锁：同一部件的安装/拆卸串行化，保证版本校验与区间变更原子。 */
    public Optional<Component> findComponentForUpdate(String componentId) {
        List<Component> rows = jdbc.query(
                "SELECT component_id, lifespan_minutes, accumulated_minutes, version"
                        + " FROM component WHERE component_id = ? FOR UPDATE",
                COMPONENT_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    public void incrementVersion(String componentId) {
        jdbc.update("UPDATE component SET version = version + 1 WHERE component_id = ?", componentId);
    }

    /** 拆卸结算：累计分钟增加 segment，版本加一。 */
    public void settleOnRemoval(String componentId, long segmentMinutes) {
        jdbc.update("UPDATE component SET accumulated_minutes = accumulated_minutes + ?,"
                        + " version = version + 1 WHERE component_id = ?",
                segmentMinutes, componentId);
    }

    // ---------- 装拆账 ----------

    /** 新增一段在装区间（open_component_id = 部件编号，唯一约束保证同部件仅一处未拆）。 */
    public long insertInstall(ComponentInstall install, String requestId, Instant installedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO component_install (component_id, equipment_id, install_reading_id,"
                            + " install_sampled_at, install_cumulative_minutes, open_component_id,"
                            + " install_operator, install_request_id, installed_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, install.componentId());
            ps.setString(2, install.equipmentId());
            ps.setString(3, install.installReadingId());
            ps.setObject(4, utc(install.installSampledAt()));
            ps.setLong(5, install.installCumulativeMinutes());
            ps.setString(6, install.componentId());
            ps.setString(7, install.installOperator());
            ps.setString(8, requestId);
            ps.setObject(9, utc(installedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("装拆记录主键生成失败");
        }
        return key.longValue();
    }

    /** 关闭在装区间：写入拆卸快照并置 open_component_id 为 NULL。 */
    public void closeInstall(long installId, String removeReadingId, Instant removeSampledAt,
                             long removeCumulativeMinutes, String removeOperator,
                             String removeRequestId, Instant removedAt) {
        jdbc.update("UPDATE component_install SET remove_reading_id = ?, remove_sampled_at = ?,"
                        + " remove_cumulative_minutes = ?, open_component_id = NULL,"
                        + " remove_operator = ?, remove_request_id = ?, removed_at = ?"
                        + " WHERE install_id = ?",
                removeReadingId, utc(removeSampledAt), removeCumulativeMinutes,
                removeOperator, removeRequestId, utc(removedAt), installId);
    }

    /** 部件当前在装区间（未拆卸），无则空。 */
    public Optional<ComponentInstall> findOpenInstall(String componentId) {
        List<ComponentInstall> rows = jdbc.query(
                "SELECT " + INSTALL_COLUMNS + " FROM component_install"
                        + " WHERE component_id = ? AND open_component_id IS NOT NULL",
                INSTALL_MAPPER, componentId);
        return rows.stream().findFirst();
    }

    /** 部件全部装拆区间，按记录主键升序。 */
    public List<ComponentInstall> listInstalls(String componentId) {
        return jdbc.query(
                "SELECT " + INSTALL_COLUMNS + " FROM component_install"
                        + " WHERE component_id = ? ORDER BY install_id ASC",
                INSTALL_MAPPER, componentId);
    }
}