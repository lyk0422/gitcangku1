package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 容器隔离记录表访问。异常巡检确认时按当时箱内清单批量追加；
 * 复核放行仅更新状态与复核字段，original_location 快照永不改写。
 */
@Repository
public class ContainerIsolationRepository {

    private static final IsolationRowMapper ROW_MAPPER = new IsolationRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerIsolationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 QUARANTINED 隔离记录，保留隔离时容器位置快照。
     */
    public void insert(long inspectionId, String evidenceKey, String containerKey,
                       String originalLocation, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_isolation
                            (inspection_id, evidence_key, container_key, original_location, status,
                             review_note, reviewed_by, reviewed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, NULL, ?)
                        """,
                inspectionId, evidenceKey, containerKey, originalLocation,
                IsolationStatus.QUARANTINED.name(), now);
    }

    /**
     * 按巡检记录查询隔离清单（按隔离写入顺序）。
     */
    public List<ContainerIsolation> findByInspectionId(long inspectionId) {
        return jdbc.query(
                "SELECT * FROM container_isolation WHERE inspection_id = ? ORDER BY id",
                ROW_MAPPER, inspectionId);
    }

    /**
     * 查询容器当前仍待复核（QUARANTINED）的隔离记录（调用前必须已锁定容器行）。
     */
    public List<ContainerIsolation> findOpenByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_isolation WHERE container_key = ? AND status = ? ORDER BY id",
                ROW_MAPPER, containerKey, IsolationStatus.QUARANTINED.name());
    }

    /**
     * 复核放行：仅当仍为 QUARANTINED 时写入复核结论与时刻，历史不可覆盖。
     *
     * @return 是否成功放行
     */
    public boolean release(long id, String reviewedBy, String reviewNote, LocalDateTime reviewedAt) {
        int updated = jdbc.update("""
                        UPDATE container_isolation
                        SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                IsolationStatus.RELEASED.name(), reviewedBy, reviewNote, reviewedAt,
                id, IsolationStatus.QUARANTINED.name());
        return updated == 1;
    }

    private static final class IsolationRowMapper implements RowMapper<ContainerIsolation> {
        @Override
        public ContainerIsolation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerIsolation(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("evidence_key"),
                    rs.getString("container_key"),
                    rs.getString("original_location"),
                    IsolationStatus.valueOf(rs.getString("status")),
                    rs.getString("review_note"),
                    rs.getString("reviewed_by"),
                    rs.getObject("reviewed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
