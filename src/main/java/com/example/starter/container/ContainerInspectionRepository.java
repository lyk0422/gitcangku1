package com.example.starter.container;

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
 * 容器巡检记录表访问。记录只追加、不可变；inspect_key 全局唯一但失败不占键
 * （失败事务回滚后键可重新使用）。
 */
@Repository
public class ContainerInspectionRepository {

    private static final InspectionRowMapper ROW_MAPPER = new InspectionRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加巡检记录，返回生成的主键。
     */
    public long insert(String containerId, String inspectKey, String inspectorId, long version,
                       LocalDateTime inspectedAt, LocalDateTime nextInspectionAt,
                       InspectionResult result, String note, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO container_inspection
                                (container_id, inspect_key, inspector_id, container_version,
                                 inspected_at, next_inspection_at, result, note, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, containerId);
            ps.setString(2, inspectKey);
            ps.setString(3, inspectorId);
            ps.setLong(4, version);
            ps.setObject(5, inspectedAt);
            ps.setObject(6, nextInspectionAt);
            ps.setString(7, result.name());
            ps.setString(8, note);
            ps.setObject(9, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按巡检幂等键查询首次成功结果。
     */
    public Optional<ContainerInspection> findByInspectKey(String inspectKey) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE inspect_key = ?", ROW_MAPPER, inspectKey);
        return rows.stream().findFirst();
    }

    /**
     * 按容器查询全部巡检记录（按发生顺序）。
     */
    public List<ContainerInspection> findByContainerId(String containerId) {
        return jdbc.query(
                "SELECT * FROM container_inspection WHERE container_id = ? ORDER BY id",
                ROW_MAPPER, containerId);
    }

    private static final class InspectionRowMapper implements RowMapper<ContainerInspection> {
        @Override
        public ContainerInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerInspection(
                    rs.getLong("id"),
                    rs.getString("container_id"),
                    rs.getString("inspect_key"),
                    rs.getString("inspector_id"),
                    rs.getLong("container_version"),
                    rs.getObject("inspected_at", LocalDateTime.class),
                    rs.getObject("next_inspection_at", LocalDateTime.class),
                    InspectionResult.valueOf(rs.getString("result")),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
