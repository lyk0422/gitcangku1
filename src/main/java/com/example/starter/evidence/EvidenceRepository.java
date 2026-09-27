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
 * 跨案移交通过 {@link #findByKeysForUpdate} 按规范顺序整批加锁，避免交叉死锁，
 * 保证并发交接/取消/核验/移交按事务提交顺序生效。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED、封签版本 1，保管人为入库操作人。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String location, String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             location, seal_version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, caseKey, category, sealNo, custodianId,
                EvidenceStatus.SEALED.name(), location, 1, now, now);
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
     * 按业务键集合批量查询并锁定证物行（SELECT ... FOR UPDATE）。
     * 调用方须先对键规范化排序后再传入，保证多事务按相同顺序加锁，避免交叉死锁。
     */
    public List<Evidence> findByKeysForUpdate(List<String> evidenceKeys) {
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
     * 跨案移交：同事务原子切换案件归属、保管人与封签版本。
     */
    public void switchCase(String evidenceKey, String toCaseKey, String toCustodian,
                           int sealVersion, LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET case_key = ?, custodian_id = ?, seal_version = ?, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                toCaseKey, toCustodian, sealVersion, now, evidenceKey);
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
                    rs.getString("location"),
                    rs.getInt("seal_version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
