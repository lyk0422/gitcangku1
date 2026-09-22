package com.example.starter.blind;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 盲法实验数据访问层；处理代码仅在本层与数据库之间流转，不写入日志。
 */
@Repository
public class ExperimentRepository {

    /** 实验行记录。 */
    public record ExperimentRow(String experimentId, int blockCount, String status) {
    }

    /** 席位行记录，含处理代码，禁止直接输出到普通查询。 */
    public record SeatRow(String experimentId, int blockNo, int seatNo, String treatmentCode,
                          String participantId, String blindCode, String seatStatus) {
    }

    /** 揭盲申请行记录。 */
    public record UnblindRow(String unblindId, String experimentId, int blockNo, int seatNo,
                             String participantId, String applicantId, String reason,
                             String status, String approverId) {
    }

    /** 幂等去重记录。 */
    public record IdempotencyRow(String requestId, String actorId, String actorRole, String action,
                                 String paramsHash, int responseStatus, String responseBody) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("experiment_id"), rs.getInt("block_count"), rs.getString("status"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
            rs.getString("experiment_id"), rs.getInt("block_no"), rs.getInt("seat_no"),
            rs.getString("treatment_code"), rs.getString("participant_id"),
            rs.getString("blind_code"), rs.getString("seat_status"));

    private static final RowMapper<UnblindRow> UNBLIND_MAPPER = (rs, n) -> new UnblindRow(
            rs.getString("unblind_id"), rs.getString("experiment_id"), rs.getInt("block_no"),
            rs.getInt("seat_no"), rs.getString("participant_id"), rs.getString("applicant_id"),
            rs.getString("reason"), rs.getString("status"), rs.getString("approver_id"));

    private static final RowMapper<IdempotencyRow> IDEMPOTENCY_MAPPER = (rs, n) -> new IdempotencyRow(
            rs.getString("request_id"), rs.getString("actor_id"), rs.getString("actor_role"),
            rs.getString("action"), rs.getString("params_hash"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertExperiment(String experimentId, int blockCount, LocalDateTime now) {
        jdbc.update("INSERT INTO experiment (experiment_id, block_count, status, created_at)"
                        + " VALUES (?, ?, 'OPEN', ?)",
                experimentId, blockCount, Timestamp.valueOf(now));
    }

    public Optional<ExperimentRow> findExperiment(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT experiment_id, block_count, status FROM experiment WHERE experiment_id = ?",
                EXPERIMENT_MAPPER, experimentId);
        return rows.stream().findFirst();
    }

    /** 在当前事务内锁定实验行，用于串行化同一实验的揭盲申请。 */
    public void lockExperiment(String experimentId) {
        jdbc.query("SELECT experiment_id FROM experiment WHERE experiment_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), experimentId);
    }

    /** 仅当实验仍处于 expected 状态时更新为目标状态，返回受影响行数。 */
    public int updateExperimentStatus(String experimentId, String expected, String target) {
        return jdbc.update("UPDATE experiment SET status = ? WHERE experiment_id = ? AND status = ?",
                target, experimentId, expected);
    }

    public void insertSeat(String experimentId, int blockNo, int seatNo, String treatmentCode) {
        jdbc.update("INSERT INTO experiment_seat"
                        + " (experiment_id, block_no, seat_no, treatment_code, seat_status)"
                        + " VALUES (?, ?, ?, ?, 'EMPTY')",
                experimentId, blockNo, seatNo, treatmentCode);
    }

    /** 按区组、席位顺序列出全部空位，供原子领取尝试。 */
    public List<SeatRow> findEmptySeats(String experimentId) {
        return jdbc.query("SELECT experiment_id, block_no, seat_no, treatment_code, participant_id,"
                        + " blind_code, seat_status FROM experiment_seat"
                        + " WHERE experiment_id = ? AND seat_status = 'EMPTY'"
                        + " ORDER BY block_no, seat_no",
                SEAT_MAPPER, experimentId);
    }

    /** 原子领取指定空位；仅当仍为空位时成功，返回受影响行数。 */
    public int claimSeat(String experimentId, int blockNo, int seatNo,
                         String participantId, String blindCode, LocalDateTime now) {
        return jdbc.update("UPDATE experiment_seat"
                        + " SET participant_id = ?, blind_code = ?, seat_status = 'ASSIGNED', assigned_at = ?"
                        + " WHERE experiment_id = ? AND block_no = ? AND seat_no = ? AND seat_status = 'EMPTY'",
                participantId, blindCode, Timestamp.valueOf(now), experimentId, blockNo, seatNo);
    }

    public Optional<SeatRow> findSeatByParticipant(String experimentId, String participantId) {
        List<SeatRow> rows = jdbc.query(
                "SELECT experiment_id, block_no, seat_no, treatment_code, participant_id,"
                        + " blind_code, seat_status FROM experiment_seat"
                        + " WHERE experiment_id = ? AND participant_id = ?",
                SEAT_MAPPER, experimentId, participantId);
        return rows.stream().findFirst();
    }

    /** 退组：不释放席位、不重排分配，仅当当前为已分配状态时成功。 */
    public int withdrawSeat(String experimentId, String participantId, LocalDateTime now) {
        return jdbc.update("UPDATE experiment_seat"
                        + " SET seat_status = 'WITHDRAWN', withdrawn_at = ?"
                        + " WHERE experiment_id = ? AND participant_id = ? AND seat_status = 'ASSIGNED'",
                Timestamp.valueOf(now), experimentId, participantId);
    }

    public int countPendingUnblind(String experimentId, int blockNo, int seatNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM unblind_request"
                        + " WHERE experiment_id = ? AND block_no = ? AND seat_no = ? AND status = 'PENDING'",
                Integer.class, experimentId, blockNo, seatNo);
        return count == null ? 0 : count;
    }

    public void insertUnblindRequest(UnblindRow row, LocalDateTime now) {
        jdbc.update("INSERT INTO unblind_request"
                        + " (unblind_id, experiment_id, block_no, seat_no, participant_id,"
                        + " applicant_id, reason, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)",
                row.unblindId(), row.experimentId(), row.blockNo(), row.seatNo(),
                row.participantId(), row.applicantId(), row.reason(), Timestamp.valueOf(now));
    }

    public Optional<UnblindRow> findUnblind(String experimentId, String unblindId) {
        List<UnblindRow> rows = jdbc.query(
                "SELECT unblind_id, experiment_id, block_no, seat_no, participant_id,"
                        + " applicant_id, reason, status, approver_id FROM unblind_request"
                        + " WHERE experiment_id = ? AND unblind_id = ?",
                UNBLIND_MAPPER, experimentId, unblindId);
        return rows.stream().findFirst();
    }

    /** 批准揭盲：仅当仍为待审状态时成功，返回受影响行数。 */
    public int approveUnblind(String unblindId, String approverId, LocalDateTime now) {
        return jdbc.update("UPDATE unblind_request"
                        + " SET status = 'APPROVED', approver_id = ?, approved_at = ?"
                        + " WHERE unblind_id = ? AND status = 'PENDING'",
                approverId, Timestamp.valueOf(now), unblindId);
    }

    public Optional<SeatRow> findSeat(String experimentId, int blockNo, int seatNo) {
        List<SeatRow> rows = jdbc.query(
                "SELECT experiment_id, block_no, seat_no, treatment_code, participant_id,"
                        + " blind_code, seat_status FROM experiment_seat"
                        + " WHERE experiment_id = ? AND block_no = ? AND seat_no = ?",
                SEAT_MAPPER, experimentId, blockNo, seatNo);
        return rows.stream().findFirst();
    }

    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, actor_id, actor_role, action, params_hash,"
                        + " response_status, response_body FROM idempotency_record WHERE request_id = ?",
                IDEMPOTENCY_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(IdempotencyRow row, LocalDateTime now) {
        jdbc.update("INSERT INTO idempotency_record"
                        + " (request_id, actor_id, actor_role, action, params_hash,"
                        + " response_status, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.actorId(), row.actorRole(), row.action(),
                row.paramsHash(), row.responseStatus(), row.responseBody(), Timestamp.valueOf(now));
    }

    /** 业务成功后回填占位行的响应报文，与业务变更同事务提交。 */
    public void updateIdempotencyResponse(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ?"
                        + " WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }
}
