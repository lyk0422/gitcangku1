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
 * 证物表访问。状态、保管人或库位变更必须先通过 {@link #findByKeyForUpdate}
 * （集合操作使用 {@link #findByKeysForUpdate}）锁定证物行，
 * 保证并发交接/取消/核验/借出/位置迁移按事务提交顺序生效。
 * 每次变更同时令 version 加 1，位置迁移申请携带的 expectedVersion 据此裁决。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新证物，初始状态 SEALED、未上架（location_code 为 NULL）、版本 0，保管人为入库操作人。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             location_code, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, NULL, 0, ?, ?)
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
     * 按业务键查询并锁定证物行（SELECT ... FOR UPDATE），用于一切状态/保管人/库位变更。
     */
    public Optional<Evidence> findByKeyForUpdate(String evidenceKey) {
        List<Evidence> rows = jdbc.query(
                "SELECT * FROM evidence WHERE evidence_key = ? FOR UPDATE", ROW_MAPPER, evidenceKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键集合查询并按证物键字典序锁定全部证物行（统一加锁顺序避免死锁）。
     * 调用前须保证入参集合已规范化（去重、字典序）；返回行按证物键字典序排列。
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
     * 更新证物状态与保管人（交接接受时原子切换保管人），版本号加 1。
     */
    public void updateCustody(String evidenceKey, String custodianId, EvidenceStatus status,
                              LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET custodian_id = ?, status = ?, version = version + 1, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                custodianId, status.name(), now, evidenceKey);
    }

    /**
     * 仅更新证物状态（交接发起/取消、核验失败、借出/归还），版本号加 1。
     */
    public void updateStatus(String evidenceKey, EvidenceStatus status, LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET status = ?, version = version + 1, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                status.name(), now, evidenceKey);
    }

    /**
     * 仅更新证物库位（上架或位置迁移双人确认），版本号加 1。
     */
    public void updateLocation(String evidenceKey, String locationCode, LocalDateTime now) {
        jdbc.update("""
                        UPDATE evidence
                        SET location_code = ?, version = version + 1, updated_at = ?
                        WHERE evidence_key = ?
                        """,
                locationCode, now, evidenceKey);
    }

    /**
     * 查询指定保管人当前可交接的证物（本人保管且状态 SEALED）。
     */
    public List<Evidence> findTransferable(String custodianId) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE custodian_id = ? AND status = ? ORDER BY id",
                ROW_MAPPER, custodianId, EvidenceStatus.SEALED.name());
    }

    /**
     * 查询某库位当前在库的全部证物（location_code 等于该库位），按证物键字典序返回。
     */
    public List<Evidence> findByLocation(String locationCode) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE location_code = ? ORDER BY evidence_key",
                ROW_MAPPER, locationCode);
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
                    rs.getString("location_code"),
                    rs.getLong("version"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
