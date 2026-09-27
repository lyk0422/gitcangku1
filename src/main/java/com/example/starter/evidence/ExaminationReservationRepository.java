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
 * 检验预约表访问。预约行只追加；开始/完成/取消均为条件更新（当前状态必须匹配），
 * 状态机只进不退。容量与证物占用的并发裁决由调用方在检验台行锁/证物行锁内完成。
 */
@Repository
public class ExaminationReservationRepository {

    private static final ReservationRowMapper ROW_MAPPER = new ReservationRowMapper();

    private final JdbcTemplate jdbc;

    public ExaminationReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 SCHEDULED 预约；reservation_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String reservationKey, String evidenceKey, String benchKey,
                       String examinerId, String requestedBy,
                       LocalDateTime startAt, LocalDateTime endAt, LocalDateTime createdAt) {
        jdbc.update("""
                        INSERT INTO examination_reservation
                            (reservation_key, evidence_key, bench_key, examiner_id, requested_by,
                             start_at, end_at, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                reservationKey, evidenceKey, benchKey, examinerId, requestedBy,
                startAt, endAt, ReservationStatus.SCHEDULED.name(), createdAt);
    }

    /**
     * 按业务键查询预约（不加锁）。
     */
    public Optional<ExaminationReservation> findByKey(String reservationKey) {
        List<ExaminationReservation> rows = jdbc.query(
                "SELECT * FROM examination_reservation WHERE reservation_key = ?",
                ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 按业务键查询并锁定预约行（SELECT ... FOR UPDATE），用于开始/完成/取消。
     */
    public Optional<ExaminationReservation> findByKeyForUpdate(String reservationKey) {
        List<ExaminationReservation> rows = jdbc.query(
                "SELECT * FROM examination_reservation WHERE reservation_key = ? FOR UPDATE",
                ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 条件开始：仅当仍为 SCHEDULED 时进入 IN_PROGRESS，并记录锁定的原封签版本与开始时刻。
     *
     * @return 是否成功开始（false 表示已被并发处理）
     */
    public boolean markStarted(long id, int sealVersionLocked, LocalDateTime startedAt) {
        int updated = jdbc.update("""
                        UPDATE examination_reservation
                        SET status = ?, seal_version_locked = ?, started_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ReservationStatus.IN_PROGRESS.name(), sealVersionLocked, startedAt,
                id, ReservationStatus.SCHEDULED.name());
        return updated == 1;
    }

    /**
     * 条件完成：仅当 IN_PROGRESS 时进入 COMPLETED，写入结果摘要、新封签编号与版本、完成时刻。
     *
     * @return 是否成功完成（false 表示状态已变化）
     */
    public boolean markCompleted(long id, String resultSummary, String newSealNo,
                                 Integer newSealVersion, LocalDateTime completedAt) {
        int updated = jdbc.update("""
                        UPDATE examination_reservation
                        SET status = ?, result_summary = ?, new_seal_no = ?,
                            new_seal_version = ?, completed_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ReservationStatus.COMPLETED.name(), resultSummary, newSealNo,
                newSealVersion, completedAt, id, ReservationStatus.IN_PROGRESS.name());
        return updated == 1;
    }

    /**
     * 条件取消：仅当 SCHEDULED 时进入 CANCELLED（取消即释放检验台容量与证物占用）。
     *
     * @return 是否成功取消（false 表示已开始或已处理）
     */
    public boolean cancel(long id, LocalDateTime cancelledAt) {
        int updated = jdbc.update("""
                        UPDATE examination_reservation
                        SET status = ?, cancelled_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ReservationStatus.CANCELLED.name(), cancelledAt, id,
                ReservationStatus.SCHEDULED.name());
        return updated == 1;
    }

    /**
     * 查询证物当前仍占用资源（SCHEDULED 或 IN_PROGRESS）的预约（调用前须锁定证物行）。
     */
    public List<ExaminationReservation> findActiveByEvidenceKey(String evidenceKey) {
        return jdbc.query("""
                        SELECT * FROM examination_reservation
                        WHERE evidence_key = ? AND status IN (?, ?)
                        ORDER BY id
                        """,
                ROW_MAPPER, evidenceKey,
                ReservationStatus.SCHEDULED.name(), ReservationStatus.IN_PROGRESS.name());
    }

    /**
     * 查询检验台上与给定左闭右开时段重叠、且仍占用容量（SCHEDULED 或 IN_PROGRESS）的预约。
     * 左闭右开重叠条件：已占时段开始 &lt; 新时段结束 且 已占时段结束 &gt; 新时段开始；
     * 首尾相接（一方 endAt 等于另一方 startAt）不重叠。调用前须锁定检验台行。
     */
    public List<ExaminationReservation> findActiveOverlapping(String benchKey,
                                                              LocalDateTime startAt,
                                                              LocalDateTime endAt) {
        return jdbc.query("""
                        SELECT * FROM examination_reservation
                        WHERE bench_key = ?
                          AND status IN (?, ?)
                          AND start_at < ?
                          AND end_at > ?
                        ORDER BY start_at, id
                        """,
                ROW_MAPPER, benchKey,
                ReservationStatus.SCHEDULED.name(), ReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
    }

    /**
     * 查询检验台在给定时段内重叠、且仍占用台位（SCHEDULED 或 IN_PROGRESS）的预约。
     * 已取消（CANCELLED）与已完成（COMPLETED）不再占用容量，诊断统计不计入。
     */
    public List<ExaminationReservation> findActiveByBenchInWindow(String benchKey,
                                                                  LocalDateTime from,
                                                                  LocalDateTime to) {
        return jdbc.query("""
                        SELECT * FROM examination_reservation
                        WHERE bench_key = ?
                          AND status IN (?, ?)
                          AND start_at < ?
                          AND end_at > ?
                        ORDER BY start_at, id
                        """,
                ROW_MAPPER, benchKey,
                ReservationStatus.SCHEDULED.name(), ReservationStatus.IN_PROGRESS.name(),
                to, from);
    }

    /**
     * 按发生顺序查询证物全部预约（历史查询）。
     */
    public List<ExaminationReservation> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM examination_reservation WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    private static final class ReservationRowMapper implements RowMapper<ExaminationReservation> {
        @Override
        public ExaminationReservation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExaminationReservation(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    rs.getString("bench_key"),
                    rs.getString("examiner_id"),
                    rs.getString("requested_by"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    ReservationStatus.valueOf(rs.getString("status")),
                    rs.getObject("seal_version_locked", Integer.class),
                    rs.getObject("started_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getString("result_summary"),
                    rs.getString("new_seal_no"),
                    rs.getObject("new_seal_version", Integer.class),
                    rs.getObject("cancelled_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
