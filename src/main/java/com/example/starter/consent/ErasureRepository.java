package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 旧代载荷清除任务与清除执行的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 *
 * <p>清除只作用于指定“主体＋用途＋代次”的记录：载荷物理置空并打清除标记，
 * 记录身份（主键）保留；游标计数仅统计本代，不跨代、不跨用途。
 */
@Repository
public class ErasureRepository {

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, rowNum) -> new TaskRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            ErasureTaskStatus.valueOf(rs.getString("status")),
            rs.getInt("erased_count"));

    private static final RowMapper<CacheRow> CACHE_MAPPER = (rs, rowNum) -> new CacheRow(
            rs.getString("request_id"),
            rs.getString("response_body"));

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
     * @param status      状态：PENDING 待清除 / DONE 已完成
     * @param erasedCount 累计已清除载荷条数（仅本代）
     */
    public record TaskRow(String subjectKey, Purpose purpose, int epoch,
                          ErasureTaskStatus status, int erasedCount) {
    }

    /**
     * 待擦除的缓存快照行（旧代 WRITE 幂等记录）。
     *
     * @param requestId    幂等请求标识
     * @param responseBody 成功响应快照（JSON），可能仍含原载荷
     */
    public record CacheRow(String requestId, String responseBody) {
    }

    void insertTask(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO erasure_task (subject_key, purpose, epoch, status, erased_count, request_id)"
                        + " VALUES (?, ?, ?, 'PENDING', 0, ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    Optional<TaskRow> findTask(String subjectKey, Purpose purpose, int epoch) {
        List<TaskRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, erased_count FROM erasure_task"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                TASK_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 读取任务并加行锁：串行化同一任务的并发执行，保证每批清除看到已提交的最新进度。
     */
    Optional<TaskRow> findTaskForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<TaskRow> rows = jdbc.query(
                "SELECT subject_key, purpose, epoch, status, erased_count FROM erasure_task"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                TASK_MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 按 recordKey 字典序选出本代最多 limit 条未清除记录并加行锁，保证并发执行不重复清除。
     */
    List<String> findUnerasedRecordKeys(String subjectKey, Purpose purpose, int epoch, int limit) {
        return jdbc.queryForList(
                "SELECT record_key FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND payload_erased = FALSE"
                        + " ORDER BY record_key ASC LIMIT ? FOR UPDATE",
                String.class, subjectKey, purpose.name(), epoch, limit);
    }

    /**
     * 物理清除单条记录载荷：置空并打清除标记，记录身份保留；返回实际更新行数。
     */
    int markPayloadErased(String subjectKey, Purpose purpose, int epoch, String recordKey) {
        return jdbc.update(
                "UPDATE consent_record SET payload = '', payload_erased = TRUE"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND record_key = ?"
                        + " AND payload_erased = FALSE",
                subjectKey, purpose.name(), epoch, recordKey);
    }

    int countRemaining(String subjectKey, Purpose purpose, int epoch) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_record"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ? AND payload_erased = FALSE",
                Integer.class, subjectKey, purpose.name(), epoch);
        return count == null ? 0 : count;
    }

    /**
     * 与清除同事务更新任务进度；done 为 true 时置 DONE 并记录完成时间。
     */
    void updateTaskProgress(String subjectKey, Purpose purpose, int epoch, int erasedCount, boolean done) {
        if (done) {
            jdbc.update(
                    "UPDATE erasure_task SET erased_count = ?, status = 'DONE',"
                            + " updated_at = CURRENT_TIMESTAMP, done_at = CURRENT_TIMESTAMP"
                            + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                    erasedCount, subjectKey, purpose.name(), epoch);
        } else {
            jdbc.update(
                    "UPDATE erasure_task SET erased_count = ?, status = 'PENDING',"
                            + " updated_at = CURRENT_TIMESTAMP"
                            + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                    erasedCount, subjectKey, purpose.name(), epoch);
        }
    }

    /**
     * 查出本代全部 WRITE 幂等缓存快照，用于擦除其中残留的原载荷；元数据与成功身份保留。
     */
    List<CacheRow> findWriteCache(String subjectKey, Purpose purpose, int epoch) {
        return jdbc.query(
                "SELECT request_id, response_body FROM idempotency_request"
                        + " WHERE operation = 'WRITE' AND subject_key = ? AND purpose = ? AND epoch = ?",
                CACHE_MAPPER, subjectKey, purpose.name(), epoch);
    }

    void scrubWriteCache(String requestId, String scrubbedBody) {
        jdbc.update(
                "UPDATE idempotency_request SET response_body = ? WHERE request_id = ?",
                scrubbedBody, requestId);
    }
}
