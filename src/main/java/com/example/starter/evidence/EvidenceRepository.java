package com.example.starter.evidence;

import com.example.starter.evidence.dto.BatchIntakeItem;
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
 * 保证并发交接/取消/核验按事务提交顺序生效。批量入库在同一事务内逐件插入，
 * evidence_key 唯一约束保证并发下同一证物不会部分创建。
 */
@Repository
public class EvidenceRepository {

    private static final EvidenceRowMapper ROW_MAPPER = new EvidenceRowMapper();

    private final JdbcTemplate jdbc;

    public EvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 单件入库插入新证物，初始状态 SEALED，保管人为入库操作人。
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
     * 批量入库逐件插入：全部 SEALED；DISCREPANT 项同时置 PENDING_REVIEW。
     */
    public void insertBatchItem(String intakeKey, String custodianId, BatchIntakeItem item,
                                BigDecimal measuredWeight, WeightCheck weightCheck,
                                ReviewStatus reviewStatus, LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO evidence
                            (evidence_key, case_key, category, seal_no, custodian_id, status,
                             intake_key, description, declared_weight, measured_weight,
                             weight_check, review_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.evidenceKey(), "BATCH:" + intakeKey, "BATCH", "BATCH-" + item.evidenceKey(),
                custodianId, EvidenceStatus.SEALED.name(), intakeKey, item.description(),
                item.declaredWeight(), measuredWeight, weightCheck.name(),
                reviewStatus == null ? null : reviewStatus.name(), now, now);
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
     * 查询给定证物键中已存在于系统的键（批量入库前置校验）。
     */
    public List<String> findExistingKeys(List<String> evidenceKeys) {
        if (evidenceKeys.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", evidenceKeys.stream().map(k -> "?").toList());
        return jdbc.queryForList(
                "SELECT evidence_key FROM evidence WHERE evidence_key IN (" + placeholders + ")",
                String.class, evidenceKeys.toArray());
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
     * 关闭差异复核状态（PENDING_REVIEW -&gt; REVIEWED），不可逆，仅更新一次。
     */
    public boolean closeReview(String evidenceKey, LocalDateTime now) {
        return jdbc.update("""
                        UPDATE evidence
                        SET review_status = ?, updated_at = ?
                        WHERE evidence_key = ? AND review_status = ?
                        """,
                ReviewStatus.REVIEWED.name(), now, evidenceKey,
                ReviewStatus.PENDING_REVIEW.name()) > 0;
    }

    /**
     * 查询指定保管人当前可交接的证物（本人保管、状态 SEALED 且无待复核差异）。
     */
    public List<Evidence> findTransferable(String custodianId) {
        return jdbc.query("""
                        SELECT * FROM evidence
                        WHERE custodian_id = ? AND status = ?
                          AND (review_status IS NULL OR review_status = ?)
                        ORDER BY id
                        """,
                ROW_MAPPER, custodianId, EvidenceStatus.SEALED.name(),
                ReviewStatus.REVIEWED.name());
    }

    /**
     * 按批次查询全部证物（按入库顺序）。
     */
    public List<Evidence> findByIntakeKey(String intakeKey) {
        return jdbc.query(
                "SELECT * FROM evidence WHERE intake_key = ? ORDER BY id", ROW_MAPPER, intakeKey);
    }

    /**
     * 统计批次当前待复核件数。
     */
    public int countPendingReview(String intakeKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evidence WHERE intake_key = ? AND review_status = ?",
                Integer.class, intakeKey, ReviewStatus.PENDING_REVIEW.name());
        return count == null ? 0 : count;
    }

    private static final class EvidenceRowMapper implements RowMapper<Evidence> {
        @Override
        public Evidence mapRow(ResultSet rs, int rowNum) throws SQLException {
            String weightCheck = rs.getString("weight_check");
            String reviewStatus = rs.getString("review_status");
            return new Evidence(
                    rs.getLong("id"),
                    rs.getString("evidence_key"),
                    rs.getString("case_key"),
                    rs.getString("category"),
                    rs.getString("seal_no"),
                    rs.getString("custodian_id"),
                    EvidenceStatus.valueOf(rs.getString("status")),
                    rs.getString("intake_key"),
                    rs.getString("description"),
                    rs.getBigDecimal("declared_weight"),
                    rs.getBigDecimal("measured_weight"),
                    weightCheck == null ? null : WeightCheck.valueOf(weightCheck),
                    reviewStatus == null ? null : ReviewStatus.valueOf(reviewStatus),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
