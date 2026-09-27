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
 * 跨案批量移交通过 {@link #findByKeysForUpdateOrdered} 按证物键排序统一加锁，
 * 保证并发交接/取消/核验/移交按事务提交顺序生效且不死锁。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED，保管人为入库操作人；location 为 null 表示未登记库位。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String location, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, location,
                             custodian_id, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, caseKey, category, sealNo, location,
                custodianId, EvidenceStatus.SEALED.name(), now, now);
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
     * 按给定证物键集合排序后批量锁定证物行（统一加锁顺序，避免并发事务交叉等锁死锁）。
     * 调用方负责比对返回行与请求键：缺失即证物不存在。
     */
    public List<Evidence> findByKeysForUpdateOrdered(Collection<String> evidenceKeys) {
        List<String> ordered = evidenceKeys.stream().distinct().sorted().toList();
        if (ordered.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ordered.stream().map(k -> "?").toList());
        return jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key IN (" + placeholders
                        + ") ORDER BY evidence_key FOR UPDATE",
                ROW_MAPPER, ordered.toArray());
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
     * 跨案移交事务内切换案件归属、保管人与库位；历史链事件与快照已先固化旧归属，不得借本更新改写历史。
     */
    public void reassignCase(String evidenceKey, String caseKey, String custodianId,
                             String location, LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET case_key = ?, custodian_id = ?, location = ?, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                caseKey, custodianId, location, now, evidenceKey);
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
                    rs.getString("location"),
                    rs.getString("custodian_id"),
                    EvidenceStatus.valueOf(rs.getString("status")),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
