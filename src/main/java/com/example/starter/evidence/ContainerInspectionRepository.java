package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 容器巡检记录表访问。request_id 全局唯一；记录只追加创建，
 * 第二人确认时一次性写入确认结果与响应快照，之后不再变更。
 */
@Repository
public class ContainerInspectionRepository {

    private static final InspectionRowMapper ROW_MAPPER = new InspectionRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 PENDING 巡检记录（首位保管人确认）；request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String containerKey, long containerVersion,
                       boolean sealIntact, String firstInspector,
                       int responseStatus, String responseBody, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_inspection
                            (request_id, container_key, container_version, seal_intact, status,
                             first_inspector, second_inspector, confirmed_at,
                             response_status, response_body, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                        """,
                requestId, containerKey, containerVersion, sealIntact ? 1 : 0,
                InspectionStatus.PENDING.name(), firstInspector,
                responseStatus, responseBody, now);
    }

    /**
     * 按巡检请求键查询。
     */
    public Optional<ContainerInspection> findByRequestId(String requestId) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE request_id = ?", ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 第二人确认：仅当仍为 PENDING 时写入第二巡检人、确认时刻与确认响应快照。
     *
     * @return 是否成功确认（false 表示已被并发事务先行确认）
     */
    public boolean confirm(long id, String secondInspector, LocalDateTime confirmedAt,
                           int responseStatus, String responseBody) {
        int updated = jdbc.update("""
                        UPDATE container_inspection
                        SET status = ?, second_inspector = ?, confirmed_at = ?,
                            response_status = ?, response_body = ?
                        WHERE id = ? AND status = ?
                        """,
                InspectionStatus.CONFIRMED.name(), secondInspector, confirmedAt,
                responseStatus, responseBody,
                id, InspectionStatus.PENDING.name());
        return updated == 1;
    }

    /**
     * 按容器查询全部巡检记录（按发生顺序），复核放行后历史保留。
     */
    public List<ContainerInspection> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_inspection WHERE container_key = ? ORDER BY id",
                ROW_MAPPER, containerKey);
    }

    private static final class InspectionRowMapper implements RowMapper<ContainerInspection> {
        @Override
        public ContainerInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerInspection(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("container_key"),
                    rs.getLong("container_version"),
                    rs.getInt("seal_intact") == 1,
                    InspectionStatus.valueOf(rs.getString("status")),
                    rs.getString("first_inspector"),
                    rs.getString("second_inspector"),
                    rs.getObject("confirmed_at", LocalDateTime.class),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
