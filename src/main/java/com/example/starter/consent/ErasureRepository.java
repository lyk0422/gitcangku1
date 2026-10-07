package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 清除任务持久化访问：按“主体＋用途＋代次”唯一，分批物理清除旧代载荷，所有查询使用参数化 SQL。
 */
@Repository
public class ErasureRepository {

    private static final RowMapper<ErasureTaskRow> TASK_MAPPER = (rs, rowNum) -> new ErasureTaskRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            ErasureStatus.valueOf(rs.getString("status")),
            rs.getInt("erased_count"));

    private final JdbcTemplate jdbc;

    public ErasureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 清除任务行。
     *
     * @param subjectKey  主体标识
     * @param purpose     用途
     * @param epoch       待清除的授权代次
     * @param status      状态：PENDING 待清除 / DONE 已全部清除
     * @param erasedCount 累计已物理清除载荷的记录数，不跨代累计
     */
    public record ErasureTaskRow(String subjectKey, Purpose purpose, int epoch,
                                 ErasureStatus status, int erasedCount) {
    }

    void insertTask(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO erasure_task (subject_key, purpose, epoch, status, erased_count, request_id)"
                        + " VALUES (?, ?, ?, 'PENDING', 0, ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    Optional<ErasureTaskRow> findTask(String subjectKey, Purpose purpose, int epoch) {
        List<ErasureTaskRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, erased_count FROM erasure_task"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                TASK_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 锁定任务行（FOR UPDATE），使同一任务的并发执行串行推进，避免半批与重复计数。
     */
    Optional<ErasureTaskRow> findTaskForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<ErasureTaskRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, erased_count FROM erasure_task"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                TASK_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 按 recordKey 字典序取最多 limit 条未清除载荷的记录键，仅限定指定代次。
     */
    List<String> findPendingRecordKeys(String subjectKey, Purpose purpose, int epoch, int limit) {
        return jdbc.queryForList(
                "SELECT record_key FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND payload_erased = FALSE"
                        + " ORDER BY record_key LIMIT ?",
                String.class, subjectKey, purpose.name(), epoch, limit);
    }

    /**
     * 物理清除单条记录载荷：载荷置 NULL 并打清除标记，记录身份保留；返回实际清除行数。
     */
    int erasePayload(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        return jdbc.update(
                "UPDATE consent_record SET payload = NULL, payload_erased = TRUE"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND payload_erased = FALSE",
                subjectKey, purpose.name(), epoch, recordKey);
    }

    int countPending(String subjectKey, Purpose purpose, int epoch) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND payload_erased = FALSE",
                Integer.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0 : count;
    }

    void updateTaskProgress(String subjectKey, Purpose purpose, int epoch,
                            int erasedCount, ErasureStatus status) {
        jdbc.update(
                "UPDATE erasure_task SET erased_count = ?, status = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                erasedCount, status.name(), subjectKey, purpose.name(), epoch);
    }
}
