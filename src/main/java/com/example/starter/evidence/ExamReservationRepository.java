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
 * 检验预约表访问。记录只追加，状态流转通过条件更新完成（当前状态必须匹配），
 * 历史字段（原封签、结果摘要、新封签、各时刻）写入后不可改写。
 * 时段为 UTC 左闭右开区间 [start_at, end_at)：两段区间重叠当且仅当
 * start_at &lt; 对方 end_at 且 end_at &gt; 对方 start_at。
 */
@Repository
public class ExamReservationRepository {

    private static final ReservationRowMapper ROW_MAPPER = new ReservationRowMapper();

    /** 占用检验台与证物的进行中状态。 */
    static final List<String> ACTIVE_STATUSES = List.of(
            ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name());

    private final JdbcTemplate jdbc;

    public ExamReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 RESERVED 预约；reservation_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String reservationKey, String evidenceKey, String benchKey, String examinerId,
                       LocalDateTime startAt, LocalDateTime endAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO exam_reservation
                            (reservation_key, evidence_key, bench_key, examiner_id,
                             start_at, end_at, status,
                             original_seal_no, original_seal_version, started_at,
                             result_summary, new_seal_no, new_seal_version, new_seal_note, completed_at,
                             cancel_reason, cancelled_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?)
                        """,
                reservationKey, evidenceKey, benchKey, examinerId,
                startAt, endAt, ExamReservationStatus.RESERVED.name(), createdAt);
    }

    /**
     * 按预约业务键查询（不加锁），用于只读场景。
     */
    public Optional<ExamReservation> findByKey(String reservationKey) {
        List<ExamReservation> rows = jdbc.query(
                "SELECT * FROM exam_reservation WHERE reservation_key = ?", ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 按预约业务键查询并锁定预约行（SELECT ... FOR UPDATE），用于开始/完成/取消裁决。
     */
    public Optional<ExamReservation> findByKeyForUpdate(String reservationKey) {
        List<ExamReservation> rows = jdbc.query(
                "SELECT * FROM exam_reservation WHERE reservation_key = ? FOR UPDATE",
                ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前进行中（RESERVED/IN_PROGRESS）的预约；每件证物至多一条。
     */
    public Optional<ExamReservation> findActiveByEvidenceKey(String evidenceKey) {
        List<ExamReservation> rows = jdbc.query(
                "SELECT * FROM exam_reservation WHERE evidence_key = ? AND status IN (?, ?)",
                ROW_MAPPER, evidenceKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name());
        return rows.stream().findFirst();
    }

    /**
     * 统计检验台上与指定时段（左闭右开）重叠的进行中预约数（调用前必须已锁定检验台行）。
     */
    public int countActiveOverlapping(String benchKey, LocalDateTime startAt, LocalDateTime endAt) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM exam_reservation
                        WHERE bench_key = ? AND status IN (?, ?)
                          AND start_at < ? AND end_at > ?
                        """,
                Integer.class, benchKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
        return count == null ? 0 : count;
    }

    /**
     * 查询检验台上与指定时段（左闭右开）重叠的进行中预约，按开始时刻与主键稳定排序。
     */
    public List<ExamReservation> findActiveOverlapping(String benchKey,
                                                       LocalDateTime startAt, LocalDateTime endAt) {
        return jdbc.query("""
                        SELECT * FROM exam_reservation
                        WHERE bench_key = ? AND status IN (?, ?)
                          AND start_at < ? AND end_at > ?
                        ORDER BY start_at, id
                        """,
                ROW_MAPPER, benchKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
    }

    /**
     * 开始检验：仅当仍为 RESERVED 时锁定原封签并转入 IN_PROGRESS。
     *
     * @return 是否成功（false 表示已被并发处理）
     */
    public boolean markStarted(long id, String originalSealNo, int originalSealVersion,
                               LocalDateTime startedAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, original_seal_no = ?, original_seal_version = ?, started_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.IN_PROGRESS.name(), originalSealNo, originalSealVersion,
                startedAt, id, ExamReservationStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 完成检验：仅当仍为 IN_PROGRESS 时写入结果摘要与新封签校验并转入 COMPLETED。
     *
     * @return 是否成功（false 表示已被并发处理）
     */
    public boolean markCompleted(long id, String resultSummary, String newSealNo, int newSealVersion,
                                 String newSealNote, LocalDateTime completedAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, result_summary = ?, new_seal_no = ?, new_seal_version = ?,
                            new_seal_note = ?, completed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.COMPLETED.name(), resultSummary, newSealNo, newSealVersion,
                newSealNote, completedAt, id, ExamReservationStatus.IN_PROGRESS.name());
        return updated == 1;
    }

    /**
     * 取消预约：仅当仍为 RESERVED 时转入 CANCELLED 并释放占用。
     *
     * @return 是否成功（false 表示已被并发处理）
     */
    public boolean markCancelled(long id, String cancelReason, LocalDateTime cancelledAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, cancel_reason = ?, cancelled_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.CANCELLED.name(), cancelReason, cancelledAt,
                id, ExamReservationStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 按证物查询全部预约历史（按发生顺序）。
     */
    public List<ExamReservation> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM exam_reservation WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class ReservationRowMapper implements RowMapper<ExamReservation> {
        @Override
        public ExamReservation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamReservation(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    rs.getString("bench_key"),
                    rs.getString("examiner_id"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    ExamReservationStatus.valueOf(rs.getString("status")),
                    rs.getString("original_seal_no"),
                    rs.getObject("original_seal_version", Integer.class),
                    rs.getObject("started_at", LocalDateTime.class),
                    rs.getString("result_summary"),
                    rs.getString("new_seal_no"),
                    rs.getObject("new_seal_version", Integer.class),
                    rs.getString("new_seal_note"),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getString("cancel_reason"),
                    rs.getObject("cancelled_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
