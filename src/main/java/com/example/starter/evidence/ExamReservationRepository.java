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
 * 检验预约表访问。记录只追加状态流转，开始/完成/取消均为带状态条件的更新，
 * 已终结（COMPLETED/CANCELLED）的行不会被任何后续语句改写。
 * RESERVED/IN_PROGRESS 视为占位：参与台位容量统计与证物占用判断；
 * COMPLETED/CANCELLED 释放台位与证物。
 */
@Repository
public class ExamReservationRepository {

    private static final ExamReservationRowMapper ROW_MAPPER = new ExamReservationRowMapper();

    private final JdbcTemplate jdbc;

    public ExamReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条 RESERVED 预约；reservation_key 全局唯一，冲突由唯一约束拒绝。
     */
    public void insert(String reservationKey, String evidenceKey, String benchKey,
                       LocalDateTime startAt, LocalDateTime endAt, String requestedBy,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO exam_reservation
                            (reservation_key, evidence_key, bench_key, start_at, end_at, status,
                             requested_by, started_at, completed_at, cancelled_at,
                             result_summary, new_seal_no, new_seal_check,
                             locked_seal_no, locked_seal_version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, ?, ?)
                        """,
                reservationKey, evidenceKey, benchKey, startAt, endAt,
                ExamReservationStatus.RESERVED.name(), requestedBy, now, now);
    }

    /**
     * 按预约业务键查询（不加锁），用于只读明细。
     */
    public Optional<ExamReservation> findByKey(String reservationKey) {
        List<ExamReservation> rows = jdbc.query(
                "SELECT * FROM exam_reservation WHERE reservation_key = ?", ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 按预约业务键查询并锁定行（SELECT ... FOR UPDATE），开始/完成/取消前使用。
     */
    public Optional<ExamReservation> findByKeyForUpdate(String reservationKey) {
        List<ExamReservation> rows = jdbc.query(
                "SELECT * FROM exam_reservation WHERE reservation_key = ? FOR UPDATE",
                ROW_MAPPER, reservationKey);
        return rows.stream().findFirst();
    }

    /**
     * 查询证物当前占位预约（RESERVED 或 IN_PROGRESS，调用前须已锁定证物行）。
     */
    public Optional<ExamReservation> findOccupyingByEvidenceKey(String evidenceKey) {
        List<ExamReservation> rows = jdbc.query("""
                        SELECT * FROM exam_reservation
                        WHERE evidence_key = ? AND status IN (?, ?)
                        ORDER BY id LIMIT 1
                        """,
                ROW_MAPPER, evidenceKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name());
        return rows.stream().findFirst();
    }

    /**
     * 查询证物在指定窗口内重叠的占位预约（RESERVED 或 IN_PROGRESS，调用前须已锁定证物行）。
     * 左闭右开：已存开始时间 &lt; 窗口结束 且 已存结束时间 &gt; 窗口开始，端点相接不重叠。
     */
    public List<ExamReservation> findOverlappingByEvidenceKey(String evidenceKey,
                                                              LocalDateTime startAt,
                                                              LocalDateTime endAt) {
        return jdbc.query("""
                        SELECT * FROM exam_reservation
                        WHERE evidence_key = ?
                          AND status IN (?, ?)
                          AND start_at < ?
                          AND end_at > ?
                        ORDER BY start_at, id
                        """,
                ROW_MAPPER, evidenceKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
    }

    /**
     * 统计指定检验台在 [startAt, endAt) 窗口内的占位预约数（UTC 左闭右开）。
     * 区间重叠条件：已存开始时间 &lt; 窗口结束 且 已存结束时间 &gt; 窗口开始，端点相接不重叠。
     * 调用前须已锁定检验台行。
     */
    public int countOverlappingOccupancy(String benchKey, LocalDateTime startAt, LocalDateTime endAt) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM exam_reservation
                        WHERE bench_key = ?
                          AND status IN (?, ?)
                          AND start_at < ?
                          AND end_at > ?
                        """,
                Integer.class, benchKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
        return count == null ? 0 : count;
    }

    /**
     * 查询指定检验台在 [startAt, endAt) 窗口内重叠的全部占位预约（按开始时间、主键稳定排序），
     * 用于容量诊断与失败原因明细。
     */
    public List<ExamReservation> findOverlappingOccupancy(String benchKey,
                                                          LocalDateTime startAt, LocalDateTime endAt) {
        return jdbc.query("""
                        SELECT * FROM exam_reservation
                        WHERE bench_key = ?
                          AND status IN (?, ?)
                          AND start_at < ?
                          AND end_at > ?
                        ORDER BY start_at, id
                        """,
                ROW_MAPPER, benchKey,
                ExamReservationStatus.RESERVED.name(), ExamReservationStatus.IN_PROGRESS.name(),
                endAt, startAt);
    }

    /**
     * 条件开始：仅当预约仍为 RESERVED 时写入开始时刻与原封签不可变快照，历史不可覆盖。
     *
     * @return 是否成功（false 表示已被并发开始或取消）
     */
    public boolean markStarted(long id, LocalDateTime startedAt, String lockedSealNo,
                               int lockedSealVersion, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, started_at = ?, locked_seal_no = ?, locked_seal_version = ?,
                            updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.IN_PROGRESS.name(), startedAt, lockedSealNo, lockedSealVersion,
                updatedAt, id, ExamReservationStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 条件完成：仅当检验进行中（IN_PROGRESS）时写入结果摘要与新封签校验。
     *
     * @return 是否成功（false 表示状态已变化）
     */
    public boolean markCompleted(long id, LocalDateTime completedAt, String resultSummary,
                                 String newSealNo, String newSealCheck, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, completed_at = ?, result_summary = ?,
                            new_seal_no = ?, new_seal_check = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.COMPLETED.name(), completedAt, resultSummary,
                newSealNo, newSealCheck, updatedAt, id,
                ExamReservationStatus.IN_PROGRESS.name());
        return updated == 1;
    }

    /**
     * 条件取消：仅未开始（RESERVED）的预约可取消，开始后不可取消。
     *
     * @return 是否成功（false 表示已开始或已终结）
     */
    public boolean markCancelled(long id, LocalDateTime cancelledAt, LocalDateTime updatedAt) {
        int updated = jdbc.update("""
                        UPDATE exam_reservation
                        SET status = ?, cancelled_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                ExamReservationStatus.CANCELLED.name(), cancelledAt, updatedAt,
                id, ExamReservationStatus.RESERVED.name());
        return updated == 1;
    }

    /**
     * 按证物查询全部预约（含已释放），按发生顺序稳定排序。
     */
    public List<ExamReservation> findByEvidenceKey(String evidenceKey) {
        return jdbc.query(
                "SELECT * FROM exam_reservation WHERE evidence_key = ? ORDER BY id",
                ROW_MAPPER, evidenceKey);
    }

    /**
     * 按检验台查询全部预约（含已释放），按开始时间与主键稳定排序。
     */
    public List<ExamReservation> findByBenchKey(String benchKey) {
        return jdbc.query(
                "SELECT * FROM exam_reservation WHERE bench_key = ? ORDER BY start_at, id",
                ROW_MAPPER, benchKey);
    }

    private static final class ExamReservationRowMapper implements RowMapper<ExamReservation> {
        @Override
        public ExamReservation mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ExamReservation(
                    rs.getLong("id"),
                    rs.getString("reservation_key"),
                    rs.getString("evidence_key"),
                    rs.getString("bench_key"),
                    rs.getObject("start_at", LocalDateTime.class),
                    rs.getObject("end_at", LocalDateTime.class),
                    ExamReservationStatus.valueOf(rs.getString("status")),
                    rs.getString("requested_by"),
                    rs.getObject("started_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getObject("cancelled_at", LocalDateTime.class),
                    rs.getString("result_summary"),
                    rs.getString("new_seal_no"),
                    rs.getString("new_seal_check"),
                    rs.getString("locked_seal_no"),
                    rs.getObject("locked_seal_version", Integer.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class));
        }
    }
}
