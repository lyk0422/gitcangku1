package com.example.starter.deacid;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 脱酸业务数据访问，基于 JdbcTemplate，全部使用参数绑定。
 */
@Repository
public class DeacidRepository {

    private static final RowMapper<TreatmentRoundView> ROUND_MAPPER = (rs, n) -> new TreatmentRoundView(
            rs.getInt("round_no"), rs.getString("batch_no"), rs.getString("batch_status"),
            rs.getBigDecimal("ph_before"), rs.getBigDecimal("ph_after"),
            rs.getString("reagent_batch_no"), rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public DeacidRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private long insert(String sql, String key) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, key);
            return ps;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    public long insertPage(String collectionNo) {
        return insert("INSERT INTO archive_page (collection_no) VALUES (?)", collectionNo);
    }

    public Optional<Long> findPageId(String collectionNo) {
        List<Long> rows = jdbc.query("SELECT id FROM archive_page WHERE collection_no = ?",
                (rs, n) -> rs.getLong(1), collectionNo);
        return rows.stream().findFirst();
    }

    public long insertBatch(String batchNo) {
        return insert("INSERT INTO deacid_batch (batch_no) VALUES (?)", batchNo);
    }

    public Optional<Long> findBatchId(String batchNo) {
        List<Long> rows = jdbc.query("SELECT id FROM deacid_batch WHERE batch_no = ?",
                (rs, n) -> rs.getLong(1), batchNo);
        return rows.stream().findFirst();
    }

    /** 锁定批次行（FOR UPDATE），返回状态；批次不存在返回空。 */
    public Optional<String> lockBatchStatus(long batchId) {
        List<String> rows = jdbc.query("SELECT status FROM deacid_batch WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), batchId);
        return rows.stream().findFirst();
    }

    public void insertMember(long batchId, long pageId) {
        jdbc.update("INSERT INTO batch_member (batch_id, page_id) VALUES (?, ?)", batchId, pageId);
    }

    public void deleteMember(long batchId, long pageId) {
        jdbc.update("DELETE FROM batch_member WHERE batch_id = ? AND page_id = ?", batchId, pageId);
    }

    public boolean memberExists(long batchId, long pageId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batch_member WHERE batch_id = ? AND page_id = ?",
                Integer.class, batchId, pageId);
        return count != null && count > 0;
    }

    public List<Long> memberPageIds(long batchId) {
        return jdbc.query("SELECT page_id FROM batch_member WHERE batch_id = ? ORDER BY page_id",
                (rs, n) -> rs.getLong(1), batchId);
    }

    public void insertOccupancy(long pageId, long batchId) {
        jdbc.update("INSERT INTO page_occupancy (page_id, batch_id) VALUES (?, ?)", pageId, batchId);
    }

    public void deleteOccupancy(long pageId) {
        jdbc.update("DELETE FROM page_occupancy WHERE page_id = ?", pageId);
    }

    /** 返回占用该页的批次主键；无占用返回空。 */
    public Optional<Long> occupancyBatchId(long pageId) {
        List<Long> rows = jdbc.query("SELECT batch_id FROM page_occupancy WHERE page_id = ?",
                (rs, n) -> rs.getLong(1), pageId);
        return rows.stream().findFirst();
    }

    public void updatePageStatus(long pageId, String status) {
        jdbc.update("UPDATE archive_page SET status = ? WHERE id = ?", status, pageId);
    }

    public int nextRoundNo(long batchId, long pageId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(round_no), 0) FROM treatment_record WHERE batch_id = ? AND page_id = ?",
                Integer.class, batchId, pageId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertTreatment(long batchId, long pageId, int roundNo,
                                BigDecimal phBefore, BigDecimal phAfter, String reagentBatchNo) {
        jdbc.update("INSERT INTO treatment_record (batch_id, page_id, round_no, ph_before, ph_after, reagent_batch_no)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                batchId, pageId, roundNo, phBefore, phAfter, reagentBatchNo);
    }

    /** 批内某页最新一轮处理记录；无记录返回空。 */
    public Optional<TreatmentRoundView> latestTreatment(long batchId, long pageId) {
        List<TreatmentRoundView> rows = jdbc.query(
                "SELECT t.round_no, b.batch_no, b.status AS batch_status, t.ph_before, t.ph_after,"
                        + " t.reagent_batch_no, t.created_at"
                        + " FROM treatment_record t JOIN deacid_batch b ON b.id = t.batch_id"
                        + " WHERE t.batch_id = ? AND t.page_id = ? ORDER BY t.round_no DESC LIMIT 1",
                ROUND_MAPPER, batchId, pageId);
        return rows.stream().findFirst();
    }

    public List<TreatmentRoundView> treatmentsOfPage(long pageId) {
        return jdbc.query(
                "SELECT t.round_no, b.batch_no, b.status AS batch_status, t.ph_before, t.ph_after,"
                        + " t.reagent_batch_no, t.created_at"
                        + " FROM treatment_record t JOIN deacid_batch b ON b.id = t.batch_id"
                        + " WHERE t.page_id = ? ORDER BY t.created_at, t.round_no",
                ROUND_MAPPER, pageId);
    }

    public int nextRejectionAttempt(long batchId) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(attempt_no), 0) FROM qc_rejection WHERE batch_id = ?",
                Integer.class, batchId);
        return (max == null ? 0 : max) + 1;
    }

    public void insertRejection(long batchId, int attemptNo, String reason) {
        jdbc.update("INSERT INTO qc_rejection (batch_id, attempt_no, reason) VALUES (?, ?, ?)",
                batchId, attemptNo, reason);
    }

    public void sealBatch(long batchId) {
        jdbc.update("UPDATE deacid_batch SET status = 'SEALED', sealed_at = CURRENT_TIMESTAMP WHERE id = ?",
                batchId);
    }

    /** 按幂等键查入组请求；未见过返回空。数组元素为 [batchId, pageId]。 */
    public Optional<long[]> findEnrollRequest(String requestId) {
        List<long[]> rows = jdbc.query("SELECT batch_id, page_id FROM enroll_request WHERE request_id = ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, requestId);
        return rows.stream().findFirst();
    }

    public void insertEnrollRequest(String requestId, long batchId, long pageId, String outcome) {
        jdbc.update("INSERT INTO enroll_request (request_id, batch_id, page_id, outcome) VALUES (?, ?, ?, ?)",
                requestId, batchId, pageId, outcome);
    }

    /** 按幂等键查封存请求；未见过返回空。detail 为问题页序列化文本。 */
    public Optional<SealResult> findSealRequest(String requestId, long batchId) {
        List<SealResult> rows = jdbc.query(
                "SELECT outcome, detail FROM seal_request WHERE request_id = ? AND batch_id = ?",
                (rs, n) -> new SealResult(rs.getString(1), DeacidService.deserializeProblems(rs.getString(2))),
                requestId, batchId);
        return rows.stream().findFirst();
    }

    /** 返回该封存幂等键首次绑定的批次主键；未见过返回空。 */
    public Optional<Long> batchIdOfSealRequest(String requestId) {
        List<Long> rows = jdbc.query("SELECT batch_id FROM seal_request WHERE request_id = ?",
                (rs, n) -> rs.getLong(1), requestId);
        return rows.stream().findFirst();
    }

    public void insertSealRequest(String requestId, long batchId, String outcome, String detail) {
        jdbc.update("INSERT INTO seal_request (request_id, batch_id, outcome, detail) VALUES (?, ?, ?, ?)",
                requestId, batchId, outcome, detail);
    }

    public String collectionNoOf(long pageId) {
        return jdbc.queryForObject("SELECT collection_no FROM archive_page WHERE id = ?",
                String.class, pageId);
    }

    public BatchView batchView(long batchId) {
        List<BatchView> rows = jdbc.query(
                "SELECT id, batch_no, status, created_at, sealed_at FROM deacid_batch WHERE id = ?",
                (rs, n) -> {
                    Timestamp sealed = rs.getTimestamp("sealed_at");
                    return new BatchView(rs.getLong("id"), rs.getString("batch_no"), rs.getString("status"),
                            List.of(), rs.getTimestamp("created_at").toLocalDateTime(),
                            sealed == null ? null : sealed.toLocalDateTime());
                }, batchId);
        if (rows.isEmpty()) {
            return null;
        }
        BatchView base = rows.get(0);
        List<String> members = jdbc.query(
                "SELECT p.collection_no FROM batch_member m JOIN archive_page p ON p.id = m.page_id"
                        + " WHERE m.batch_id = ? ORDER BY p.collection_no",
                (rs, n) -> rs.getString(1), batchId);
        return new BatchView(base.batchId(), base.batchNo(), base.status(), members,
                base.createdAt(), base.sealedAt());
    }
}