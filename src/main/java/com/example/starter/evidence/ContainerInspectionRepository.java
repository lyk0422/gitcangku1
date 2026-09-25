package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器巡检记录表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class ContainerInspectionRepository {

    private static final InspectionRowMapper ROW_MAPPER = new InspectionRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条巡检记录并返回自增主键（快照行需要引用）。
     */
    public long insert(String containerKey, String inspectorId, SealResult result, String note,
                       LocalDateTime inspectedAt, long containerVersion, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO container_inspection
                                (container_key, inspector_id, result, note, inspected_at,
                                 container_version, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, containerKey);
            ps.setString(2, inspectorId);
            ps.setString(3, result.name());
            ps.setString(4, note);
            ps.setObject(5, inspectedAt);
            ps.setLong(6, containerVersion);
            ps.setObject(7, now);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按主键查询。
     */
    public ContainerInspection findById(long id) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE id = ?", ROW_MAPPER, id);
        return rows.stream().findFirst().orElseThrow();
    }

    /**
     * 按容器查询全部巡检记录（按发生顺序）。
     */
    public List<ContainerInspection> findByContainer(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_inspection WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    /**
     * 查询容器最近一条 FAIL 巡检记录；容器处于 INSPECTION_FAILED 时必然存在。
     */
    public ContainerInspection findLatestFail(String containerKey) {
        List<ContainerInspection> rows = jdbc.query("""
                        SELECT * FROM container_inspection
                        WHERE container_key = ? AND result = 'FAIL'
                        ORDER BY id DESC LIMIT 1
                        """,
                ROW_MAPPER, containerKey);
        return rows.stream().findFirst().orElseThrow();
    }

    private static final class InspectionRowMapper implements RowMapper<ContainerInspection> {
        @Override
        public ContainerInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerInspection(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("inspector_id"),
                    SealResult.valueOf(rs.getString("result")),
                    rs.getString("note"),
                    rs.getObject("inspected_at", LocalDateTime.class),
                    rs.getLong("container_version"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
