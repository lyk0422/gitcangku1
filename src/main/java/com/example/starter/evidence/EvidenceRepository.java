package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 证物表访问。状态与保管人变更必须先通过 {@link #findByKeyForUpdate} 锁定证物行，
 * 容器巡检隔离须通过 {@link #findByKeysForUpdate} 一次性按固定顺序锁定箱内全部证物行，
 * 保证并发交接/取消/核验/隔离按事务提交顺序生效。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED，保管人为入库操作人，未放入任何容器。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id,
                             container_key, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)
                        """,
                evidenceKey, caseKey, category, sealNo, custodianId,
                EvidenceStatus.SEALED.name(), now, now);
    }

    /**
     * 按业务键查询（不加锁），用于只读场景。
     */
    public Optional<Evidence> findByKey(String evidenceKey) {
        List<Evidence> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key = ?", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定证物行（SELECT ... FOR UPDATE），用于一切状态/保管人变更。
     */
    public Optional<Evidence> findByKeyForUpdate(String evidenceKey) {
        List<Evidence> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key = ? FOR UPDATE", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键集合查询并一次性锁定证物行；按键排序加锁，避免并发事务交叉加锁死锁。
     * 用于容器异常巡检时对箱内全部证物的批量隔离。
     */
    public List<Evidence> findByKeysForUpdate(Collection<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", evidenceKeys.stream().map(k -> "?").toList());
        return jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key IN (" + placeholders
                        + ") ORDER BY evidence_key FOR UPDATE",
                ROW_MAPPER, evidenceKeys.toArray());
    }

    /**
     * 查询当前位于指定容器内的证物（不加锁，仅用于只读快照前的清单获取）。
     */
    public List<Evidence> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE container_key = ? ORDER BY evidence_key",
                ROW_MAPPER, containerKey);
    }

    /**
     * 查询当前位于指定容器内的证物并锁定容器登记行顺序，用于双人确认后的箱内清单冻结。
     */
    public List<Evidence> findByContainerKeyForUpdate(String containerKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE container_key = ? ORDER BY evidence_key FOR UPDATE",
                ROW_MAPPER, containerKey);
    }

    /**
     * 更新证物状态与保管人（交接接受时原子切换保管人）。
     */
    public void updateCustody(String evidenceKey, String custodianId, EvidenceStatus status,
                              LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET custodian_id = ?, status = ?, updated_at = ? WHERE evidence_key = ?",
                custodianId, status.name(), now, evidenceKey);
    }

    /**
     * 仅更新证物状态（交接发起/取消、核验失败、巡检隔离与复核放行）。
     */
    public void updateStatus(String evidenceKey, EvidenceStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET status = ?, updated_at = ? WHERE evidence_key = ?",
                status.name(), now, evidenceKey);
    }

    /**
     * 更新证物所在容器（放入容器）。
     */
    public void updateContainer(String evidenceKey, String containerKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET container_key = ?, updated_at = ? WHERE evidence_key = ?",
                containerKey, now, evidenceKey);
    }

    /**
     * 查询指定保管人当前可交接的证物（本人保管且状态 SEALED）。
     */
    public List<Evidence> findTransferable(String custodianId) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE custodian_id = ? AND status = ? ORDER BY id",
                ROW_MAPPER, custodianId, EvidenceStatus.SEALED.name());
    }

    private static final class EvidenceRowMapper implements RowMapper<Evidence> {
        @Override
        public Evidence mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new Evidence(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    rs.getString("category"),
                    rs.getString("seal_no"),
                    rs.getString("custodian_id"),
                    rs.getString("container_key"),
                    EvidenceStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
