package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 抽样检验计划域持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存储。
 */
@Repository
public class SamplingRepository {

    /**
     * sampling_plan 表行记录：计划参数与累计计数、判定时刻。
     */
    public record PlanRow(long id, String planKey, String batchKey, int sampleSize,
                          int acceptNumber, int rejectNumber, String basis, String status,
                          int recordedCount, int weightedDefects, String createdAt, String decidedAt) {
    }

    /**
     * sample_record 表行记录：逐件登记结果，创建后不可改写。
     * weightedDefectsAfter / planStatusAfter 为该件登记落定当时的累计计数与计划状态快照。
     */
    public record SampleRow(long id, String planKey, int sampleNo, boolean conforming,
                            String grade, String description, int weightAdded,
                            int weightedDefectsAfter, String planStatusAfter, String createdAt) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getString("plan_key"), rs.getString("batch_key"),
            rs.getInt("sample_size"), rs.getInt("accept_number"), rs.getInt("reject_number"),
            rs.getString("basis"), rs.getString("status"), rs.getInt("recorded_count"),
            rs.getInt("weighted_defects"), rs.getString("created_at"), rs.getString("decided_at"));

    private static final RowMapper<SampleRow> SAMPLE_MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"), rs.getString("plan_key"), rs.getInt("sample_no"),
            rs.getBoolean("conforming"), rs.getString("grade"), rs.getString("description"),
            rs.getInt("weight_added"), rs.getInt("weighted_defects_after"),
            rs.getString("plan_status_after"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public SamplingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlanRow> findPlan(String planKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE plan_key = ?", PLAN_MAPPER, planKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取计划，串行化同一计划上的并发登记，保证加权计数读取-累加-写回不重不漏。
     */
    public Optional<PlanRow> findPlanForUpdate(String planKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE plan_key = ? FOR UPDATE",
                        PLAN_MAPPER, planKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取某批次当前未终结(OPEN)计划；调用方须先持有该批次 batch 行锁。
     */
    public Optional<PlanRow> findOpenPlanForUpdate(String batchKey) {
        return jdbc.query(
                        "SELECT * FROM sampling_plan WHERE batch_key = ? AND status = 'OPEN' FOR UPDATE",
                        PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void insertPlan(PlanRow row) {
        jdbc.update("INSERT INTO sampling_plan (plan_key, batch_key, sample_size, accept_number,"
                        + " reject_number, basis, status, recorded_count, weighted_defects,"
                        + " created_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.planKey(), row.batchKey(), row.sampleSize(), row.acceptNumber(),
                row.rejectNumber(), row.basis(), row.status(), row.recordedCount(),
                row.weightedDefects(), row.createdAt(), row.decidedAt());
    }

    public void insertSample(SampleRow row) {
        jdbc.update("INSERT INTO sample_record (plan_key, sample_no, conforming, grade, description,"
                        + " weight_added, weighted_defects_after, plan_status_after, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.planKey(), row.sampleNo(), row.conforming(), row.grade(), row.description(),
                row.weightAdded(), row.weightedDefectsAfter(), row.planStatusAfter(), row.createdAt());
    }

    /**
     * 落定一件登记后的计划累计计数、状态与判定时刻；decidedAt 为 null 时写 NULL（保持 OPEN）。
     */
    public void updatePlanProgress(String planKey, int recordedCount, int weightedDefects,
                                   String status, String decidedAt) {
        jdbc.update("UPDATE sampling_plan SET recorded_count = ?, weighted_defects = ?,"
                        + " status = ?, decided_at = ? WHERE plan_key = ?",
                recordedCount, weightedDefects, status, decidedAt, planKey);
    }

    public List<PlanRow> findPlansByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE batch_key = ? ORDER BY id",
                PLAN_MAPPER, batchKey);
    }

    public int countRejectedPlans(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ? AND status = 'REJECTED'",
                Integer.class, batchKey);
        return count == null ? 0 : count;
    }

    public Optional<PlanRow> findAcceptedPlan(String batchKey) {
        return jdbc.query(
                        "SELECT * FROM sampling_plan WHERE batch_key = ? AND status = 'ACCEPTED'"
                                + " ORDER BY id LIMIT 1",
                        PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public List<SampleRow> findSamples(String planKey) {
        return jdbc.query("SELECT * FROM sample_record WHERE plan_key = ? ORDER BY sample_no",
                SAMPLE_MAPPER, planKey);
    }
}
