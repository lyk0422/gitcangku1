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
 * 证物表访问。状态、保管人与案件归属变更必须先通过 {@link #findByKeyForUpdate} 或
 * {@link #findByKeysForUpdate} 锁定证物行，保证并发交接/跨案移交按事务提交顺序生效。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED，封签版本固化为 1，位置未登记，保管人为入库操作人。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             location, seal_version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, 1, ?, ?)
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
     * 按多个业务键查询并锁定证物行；按证物键升序加锁，保证多证物批次固定加锁顺序避免死锁。
     * 调用方须保证 keys 非空且已去重；返回行序与键序一致。
     */
    public List<Evidence> findByKeysForUpdate(Collection<String> keys) {
        String placeholders = String.join(",", keys.stream().map(k -> "?").toList());
        List<String> orderedKeys = keys.stream().sorted().toList();
        return jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key IN (" + placeholders + ") "
                        + "ORDER BY evidence_key FOR UPDATE",
                ROW_MAPPER, orderedKeys.toArray());
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
     * 跨案移交原子切换案件归属、保管人与存放位置；状态回到 SEALED。
     * location 为 null 时保持原位置不变（COALESCE）。
     */
    public void updateCaseCustody(String evidenceKey, String caseKey, String custodianId,
                                  String location, LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET case_key = ?, custodian_id = ?, status = ?,
                            location = COALESCE(?, location), updated_at = ?
                        WHERE evidence_key = ?
                        """,
                caseKey, custodianId, EvidenceStatus.SEALED.name(), location, now, evidenceKey);
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
