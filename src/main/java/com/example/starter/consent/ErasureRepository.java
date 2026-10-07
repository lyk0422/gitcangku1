package com.example.starter.consent;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 清除任务持久化访问：按“主体＋用途＋代次”唯一，进度更新与载荷清除在同一事务提交。
 */
@Repository
public class ErasureRepository {

    private static final RowMapper<ErasureTaskRow> MAPPER = (rs, rowNum) -> new ErasureTaskRow(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            ErasureStatus.valueOf(rs.getString("status")),
            rs.getInt("erased_count"));

    private static final String SELECT_COLUMNS =
            "SELECT subject_key, purpose, epoch, status, erased_count FROM erasure_task";

    private final JdbcTemplate jdbc;

    public ErasureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 清除任务行。
     *
     * @param subjectKey  主体标识（合成字符串）
     * @param purpose     用途
     * @param epoch       已撤回的授权代次
     * @param status      状态：PENDING 待清除 / DONE 已清完
     * @param erasedCount 累计已清除载荷条数，不跨代统计
     */
    public record ErasureTaskRow(String subjectKey, Purpose purpose, int epoch,
                                 ErasureStatus status, int erasedCount) {
    }

    Optional<ErasureTaskRow> findTask(String subjectKey, Purpose purpose, int epoch) {
        List<ErasureTaskRow> rows = jdbc.query(
                SELECT_COLUMNS + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 执行清除时以行锁读取任务，串行化并发执行，保证批次与累计数不错乱。
     */
    Optional<ErasureTaskRow> findTaskForUpdate(String subjectKey, Purpose purpose, int epoch) {
        List<ErasureTaskRow> rows = jdbc.query(
                SELECT_COLUMNS + " WHERE subject_key = ? AND purpose = ? AND epoch = ? FOR UPDATE",
                MAPPER, subjectKey, purpose.name(), epoch);
        return rows.stream().findFirst();
    }

    /**
     * 撤回成功时建立唯一 PENDING 任务；主键冲突表示并发撤回已建任务，由调用方忽略。
     */
    void insertTask(String subjectKey, Purpose purpose, int epoch, String requestId) {
        jdbc.update(
                "INSERT INTO erasure_task (subject_key, purpose, epoch, status, erased_count, request_id)"
                        + " VALUES (?, ?, ?, 'PENDING', 0, ?)",
                subjectKey, purpose.name(), epoch, requestId);
    }

    /**
     * 与本批载荷清除同事务更新累计清除数与状态。
     */
    void updateProgress(String subjectKey, Purpose purpose, int epoch, int erasedCount, ErasureStatus status) {
        jdbc.update(
                "UPDATE erasure_task SET erased_count = ?, status = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE subject_key = ? AND purpose = ? AND epoch = ?",
                erasedCount, status.name(), subjectKey, purpose.name(), epoch);
    }
}
