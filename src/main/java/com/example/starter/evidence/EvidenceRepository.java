package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
     * 插入新证物，初始状态 SEALED，保管人为入库操作人。
     */
    public void insert(String evidenceKey, String caseKey, String category, String sealNo,
                       String custodianId, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, caseKey, category, sealNo, custodianId,
                EvidenceStatus.SEALED.name(), now, now);
    }

    /**
     * 批量入库插入新证物，初始状态 SEALED，携带重量核对与复核状态。
     */
    public void insertBatch(String evidenceKey, String custodianId, String description,
                            BigDecimal declaredWeight, BigDecimal measuredWeight,
                            WeightStatus weightStatus, ReviewStatus reviewStatus,
                            String intakeKey, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             description, declared_weight, measured_weight,
                             weight_status, review_status, intake_key, created_at, updated_at)
                        VALUES (?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                evidenceKey, custodianId, EvidenceStatus.SEALED.name(),
                description, declaredWeight, measuredWeight,
                weightStatus.name(), reviewStatus.name(), intakeKey, now, now);
    }

    /**
     * 按批次键查询该批全部证物（按主键顺序，即清单创建顺序）。
     */
    public List<Evidence> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE intake_key = ? ORDER BY id",
                ROW_MAPPER, intakeKey);
    }

    /**
     * 关闭待复核状态（PENDING -> RESOLVED）；仅当前仍为 PENDING 时生效，返回是否更新成功。
     */
    public boolean closeReview(String evidenceKey, LocalDateTime now) {
        return jdbc.update(
                "UPDATE evidence SET review_status = ?, updated_at = ? "
                        + "WHERE evidence_key = ? AND review_status = ?",
                ReviewStatus.RESOLVED.name(), now, evidenceKey, ReviewStatus.PENDING.name()) == 1;
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
                    rs.getString("description"),
                    rs.getBigDecimal("declared_weight"),
                    rs.getBigDecimal("measured_weight"),
                    rs.getString("weight_status") == null
                            ? null : WeightStatus.valueOf(rs.getString("weight_status")),
                    ReviewStatus.valueOf(rs.getString("review_status")),
                    rs.getString("intake_key"),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
