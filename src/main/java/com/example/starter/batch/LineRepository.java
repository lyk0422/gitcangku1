package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 生产线加工顺序与清洗验证持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class LineRepository {

    /**
     * production_line 表行记录；next_seq 同时作为线路乐观版本（登记期望版本）。
     */
    public record LineRow(String lineId, int nextSeq, String createdAt) {
    }

    /**
     * line_sequence 表行记录：加工顺序登记，创建后不可改写。
     */
    public record LineSequenceRow(long id, String lineId, int seq, String batchKey,
                                  String prevBatchKey, String requestId, String operatorId,
                                  String createdAt) {
    }

    /**
     * cleaning_record 表行记录：清洗记录只增不改，确认后 version 由 0 变为 1。
     */
    public record CleaningRow(long id, String cleaningId, String lineId, String fromBatchKey,
                              String toBatchKey, String residualAmount, String status,
                              String submittedBy, String submittedAt, String confirmedBy,
                              String confirmedAt, int version) {
    }

    private static final RowMapper<LineRow> LINE_MAPPER = (rs, n) -> new LineRow(
            rs.getString("line_id"), rs.getInt("next_seq"), rs.getString("created_at"));

    private static final RowMapper<LineSequenceRow> SEQUENCE_MAPPER = (rs, n) -> new LineSequenceRow(
            rs.getLong("id"), rs.getString("line_id"), rs.getInt("seq"),
            rs.getString("batch_key"), rs.getString("prev_batch_key"),
            rs.getString("request_id"), rs.getString("operator_id"), rs.getString("created_at"));

    private static final RowMapper<CleaningRow> CLEANING_MAPPER = (rs, n) -> new CleaningRow(
            rs.getLong("id"), rs.getString("cleaning_id"), rs.getString("line_id"),
            rs.getString("from_batch_key"), rs.getString("to_batch_key"),
            rs.getString("residual_amount"), rs.getString("status"),
            rs.getString("submitted_by"), rs.getString("submitted_at"),
            rs.getString("confirmed_by"), rs.getString("confirmed_at"), rs.getInt("version"));

    private final JdbcTemplate jdbc;

    public LineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LineRow> findLine(String lineId) {
        return jdbc.query("SELECT * FROM production_line WHERE line_id = ?", LINE_MAPPER, lineId)
                .stream().findFirst();
    }

    /**
     * 行锁读取生产线，串行化该线上的登记与清洗提交事务。
     */
    public Optional<LineRow> findLineForUpdate(String lineId) {
        return jdbc.query("SELECT * FROM production_line WHERE line_id = ? FOR UPDATE",
                        LINE_MAPPER, lineId)
                .stream().findFirst();
    }

    public void insertLine(String lineId, int nextSeq, String createdAt) {
        jdbc.update("INSERT INTO production_line (line_id, next_seq, created_at) VALUES (?, ?, ?)",
                lineId, nextSeq, createdAt);
    }

    public void updateNextSeq(String lineId, int nextSeq) {
        jdbc.update("UPDATE production_line SET next_seq = ? WHERE line_id = ?", nextSeq, lineId);
    }

    /**
     * 按批次业务键查登记记录；批次全局仅可登记一次（禁止一批挂多线）。
     */
    public Optional<LineSequenceRow> findSequenceByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM line_sequence WHERE batch_key = ?",
                        SEQUENCE_MAPPER, batchKey)
                .stream().findFirst();
    }

    /**
     * 生产线上全部登记，按顺序号升序。
     */
    public List<LineSequenceRow> findSequences(String lineId) {
        return jdbc.query("SELECT * FROM line_sequence WHERE line_id = ? ORDER BY seq",
                SEQUENCE_MAPPER, lineId);
    }

    /**
     * 某顺序号之前的全部前批登记，按顺序号升序。
     */
    public List<LineSequenceRow> findPredecessors(String lineId, int seq) {
        return jdbc.query("SELECT * FROM line_sequence WHERE line_id = ? AND seq < ? ORDER BY seq",
                SEQUENCE_MAPPER, lineId, seq);
    }

    public void insertSequence(LineSequenceRow row) {
        jdbc.update("INSERT INTO line_sequence (line_id, seq, batch_key, prev_batch_key,"
                        + " request_id, operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.lineId(), row.seq(), row.batchKey(), row.prevBatchKey(),
                row.requestId(), row.operatorId(), row.createdAt());
    }
    /**
     * 某（线, 前批, 后批）组合已有的清洗记录数；作为清洗提交的期望版本基准。
     */
    public int countCleanings(String lineId, String fromBatchKey, String toBatchKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM cleaning_record"
                        + " WHERE line_id = ? AND from_batch_key = ? AND to_batch_key = ?",
                Integer.class, lineId, fromBatchKey, toBatchKey);
        return count == null ? 0 : count;
    }

    public Optional<CleaningRow> findCleaning(String cleaningId) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE cleaning_id = ?",
                        CLEANING_MAPPER, cleaningId)
                .stream().findFirst();
    }

    /**
     * 行锁读取清洗记录，串行化确认与放行冻结检查。
     */
    public Optional<CleaningRow> findCleaningForUpdate(String cleaningId) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE cleaning_id = ? FOR UPDATE",
                        CLEANING_MAPPER, cleaningId)
                .stream().findFirst();
    }

    public void insertCleaning(CleaningRow row) {
        jdbc.update("INSERT INTO cleaning_record (cleaning_id, line_id, from_batch_key,"
                        + " to_batch_key, residual_amount, status, submitted_by, submitted_at,"
                        + " confirmed_by, confirmed_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.cleaningId(), row.lineId(), row.fromBatchKey(), row.toBatchKey(),
                row.residualAmount(), row.status(), row.submittedBy(), row.submittedAt(),
                row.confirmedBy(), row.confirmedAt(), row.version());
    }

    /**
     * 某（线, 前批, 后批）组合下已确认的清洗记录，行锁读取用于放行冻结；
     * 残留量是否合格由服务层按未舍入原值判定。
     */
    public List<CleaningRow> findConfirmedCleaningsForUpdate(String lineId, String fromBatchKey,
                                                             String toBatchKey) {
        return jdbc.query("SELECT * FROM cleaning_record WHERE line_id = ?"
                        + " AND from_batch_key = ? AND to_batch_key = ? AND status = 'CONFIRMED'"
                        + " ORDER BY id FOR UPDATE",
                CLEANING_MAPPER, lineId, fromBatchKey, toBatchKey);
    }

    /**
     * 确认清洗：状态置 CONFIRMED、记录确认人与确认时间、版本由 0 升为 1；记录只增不改。
     */
    public void confirmCleaning(String cleaningId, String confirmedBy, String confirmedAt) {
        jdbc.update("UPDATE cleaning_record SET status = 'CONFIRMED', confirmed_by = ?,"
                        + " confirmed_at = ?, version = 1 WHERE cleaning_id = ?",
                confirmedBy, confirmedAt, cleaningId);
    }
}