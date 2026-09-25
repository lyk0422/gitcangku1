package com.example.starter.container;

import com.example.starter.container.dto.PendingVerificationView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 容器装载关系表访问。FAIL 容器集合冻结由服务层在锁定容器行后保证；
 * evidence_key 唯一约束保证一件证物同一时刻至多装入一个容器。
 */
@Repository
public class ContainerItemRepository {

    private final JdbcTemplate jdbc;

    public ContainerItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 装载一件证物（重复装载同一容器内的同一证物会被唯一约束拒绝）。
     */
    public void insert(String containerId, String evidenceKey, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_item (container_id, evidence_key, loaded_at)
                        VALUES (?, ?, ?)
                        """,
                containerId, evidenceKey, now);
    }

    /**
     * 移出一件证物。
     *
     * @return 删除行数；0 表示证物不在容器中
     */
    public int delete(String containerId, String evidenceKey) {
        return jdbc.update(
                "DELETE FROM container_item WHERE container_id = ? AND evidence_key = ?",
                containerId, evidenceKey);
    }

    /**
     * 查询容器当前装载证物键（按装载顺序）。
     */
    public List<String> findEvidenceKeys(String containerId) {
        return jdbc.queryForList(
                "SELECT evidence_key FROM container_item WHERE container_id = ? ORDER BY id",
                String.class, containerId);
    }

    /**
     * 查询证物当前所在容器（不加锁，供借出/迁移门禁快速判断）。
     */
    public Optional<String> findContainerOfEvidence(String evidenceKey) {
        List<String> rows = jdbc.queryForList(
                "SELECT container_id FROM container_item WHERE evidence_key = ?",
                String.class, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询待核验证物：证据状态为 PENDING_VERIFICATION，并关联触发该状态的最近 FAIL 巡检。
     * custodianId 非空时按保管人过滤。
     */
    public List<PendingVerificationView> findPendingVerification(String custodianId) {
        String sql = """
                SELECT e.evidence_key, e.custodian_id, e.status, ci.container_id,
                       sn.inspection_id AS failed_inspection_id,
                       cin.inspected_at AS failed_at
                FROM evidence e
                JOIN container_item ci ON ci.evidence_key = e.evidence_key
                JOIN (
                    SELECT evidence_key, MAX(inspection_id) AS inspection_id
                    FROM container_item_snapshot
                    GROUP BY evidence_key
                ) sn ON sn.evidence_key = e.evidence_key
                JOIN container_inspection cin ON cin.id = sn.inspection_id
                WHERE e.status = 'PENDING_VERIFICATION'
                """ + (custodianId == null || custodianId.isBlank() ? ""
                : " AND e.custodian_id = ?") + """
                 ORDER BY cin.id, e.id
                """;
        if (custodianId == null || custodianId.isBlank()) {
            return jdbc.query(sql, this::mapPending);
        }
        return jdbc.query(sql, this::mapPending, custodianId);
    }

    private PendingVerificationView mapPending(ResultSet rs, int rowNum) throws SQLException {
        return new PendingVerificationView(
                rs.getString("evidence_key"),
                rs.getString("custodian_id"),
                rs.getString("status"),
                rs.getString("container_id"),
                rs.getLong("failed_inspection_id"),
                rs.getObject("failed_at", LocalDateTime.class));
    }
}
