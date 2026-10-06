package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 箱封条核验记录表访问。记录只追加、不可变，不提供任何更新语句。
 */
@Repository
public class ContainerSealInspectionRepository {

    private static final InspectionRowMapper ROW_MAPPER = new InspectionRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerSealInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条箱封条核验记录。
     */
    public void insert(String containerKey, String inspectorId, boolean passed, String note,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_seal_inspection
                            (container_key, inspector_id, passed, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                containerKey, inspectorId, passed ? 1 : 0, note, now);
    }

    /**
     * 按发生顺序查询箱全部核验记录。
     */
    public List<ContainerSealInspection> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_seal_inspection WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    private static final class InspectionRowMapper implements RowMapper<ContainerSealInspection> {
        @Override
        public ContainerSealInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerSealInspection(
                    rs.getLong("id"),
                    rs.getString("container_key"),
                    rs.getString("inspector_id"),
                    rs.getInt("passed") == 1,
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
