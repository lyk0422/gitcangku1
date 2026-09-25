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
 * 证物表访问。状态与保管人变更必须先通过 {@link #findByKeyForUpdate} 锁定证物行，
 * 保证并发交接/取消/核验按事务提交顺序生效。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED，保管人为入库操作人，不属于任何容器。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             container_key, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
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
     * 更新证物状态与保管人（交接接受时原子切换保管人）。
     */
    public void updateCustody(String evidenceKey, String custodianId, EvidenceStatus status,
                              LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET custodian_id = ?, status = ?, updated_at = ? WHERE evidence_key = ?",
                custodianId, status.name(), now, evidenceKey);
    }

    /**
     * 仅更新证物状态（交接发起/取消、核验失败）。
     */
    public void updateStatus(String evidenceKey, EvidenceStatus status, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET status = ?, updated_at = ? WHERE evidence_key = ?",
                status.name(), now, evidenceKey);
    }

    /**
     * 仅更新证物所属容器（装载/移出）；null 表示移出容器。
     */
    public void updateContainer(String evidenceKey, String containerKey, LocalDateTime now) {
        jdbc.update(
                "UPDATE evidence SET container_key = ?, updated_at = ? WHERE evidence_key = ?",
                containerKey, now, evidenceKey);
    }

    /**
     * 条件更新状态（CAS）：仅当当前状态等于期望值时生效，用于并发裁决。
     *
     * @return 是否更新成功（false 表示已被并发事务改变状态）
     */
    public boolean compareAndUpdateStatus(String evidenceKey, EvidenceStatus expect,
                                          EvidenceStatus target, LocalDateTime now) {
        int updated = jdbc.update("""
                        UPDATE evidence
                        SET status = ?, updated_at = ?
                        WHERE evidence_key = ? AND status = ?
                        """,
                target.name(), now, evidenceKey, expect.name());
        return updated == 1;
    }

    /**
     * 查询容器内全部证物并锁定行（FAIL 巡检批量标记与快照必须逐件锁定）。
     */
    public List<Evidence> findByContainerForUpdate(String containerKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE container_key = ? ORDER BY evidence_key FOR UPDATE",
                ROW_MAPPER, containerKey);
    }

    /**
     * 查询容器内全部证物（只读，按证物键排序，集合顺序不影响业务语义）。
     */
    public List<Evidence> findByContainer(String containerKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE container_key = ? ORDER BY evidence_key",
                ROW_MAPPER, containerKey);
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
                    EvidenceStatus.valueOf(rs.getString("status")),
                    rs.getString("container_key"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
