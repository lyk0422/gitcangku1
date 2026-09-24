package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 抽样检验计划持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 */
@Repository
public class SamplingPlanRepository {

    /**
     * sampling_plan 表行记录；seq 为同批次计划序号，id 作为创建顺序依据。
     */
    public record PlanRow(long id, String planKey, String batchKey, int seq, int sampleSize,
                          int acceptNumber, int rejectNumber, String basis, String status,
                          int weightedDefects, int registeredCount, String createdAt,
                          String decidedAt) {
    }

    /**
     * sample_record 表行记录；登记后不可修改。
     */
    public record SampleRecordRow(long id, String planKey, int sampleIndex, String result,
                                  String description, int weight, String createdAt) {
    }

    private static final RowMapper<PlanRow> PLAN_MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"), rs.getString("plan_key"), rs.getString("batch_key"),
            rs.getInt("seq"), rs.getInt("sample_size"), rs.getInt("accept_number"),
            rs.getInt("reject_number"), rs.getString("basis"), rs.getString("status"),
            rs.getInt("weighted_defects"), rs.getInt("registered_count"),
            rs.getString("created_at"), rs.getString("decided_at"));

    private static final RowMapper<SampleRecordRow> SAMPLE_MAPPER = (rs, n) -> new SampleRecordRow(
            rs.getLong("id"), rs.getString("plan_key"), rs.getInt("sample_index"),
            rs.getString("result"), rs.getString("description"), rs.getInt("weight"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public SamplingPlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlanRow> findPlan(String planKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE plan_key = ?", PLAN_MAPPER, planKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取计划，串行化同一计划上的并发登记事务。
     */
    public Optional<PlanRow> findPlanForUpdate(String planKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE plan_key = ? FOR UPDATE",
                        PLAN_MAPPER, planKey)
                .stream().findFirst();
    }

    /**
     * 行锁读取某批次当前未终结（OPEN）计划；不存在返回空。
     */
    public Optional<PlanRow> findOpenPlanByBatchForUpdate(String batchKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE batch_key = ? AND status = 'OPEN' FOR UPDATE",
                        PLAN_MAPPER, batchKey)
                .stream().findFirst();
    }

    public List<PlanRow> findPlansByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM sampling_plan WHERE batch_key = ? ORDER BY seq",
                PLAN_MAPPER, batchKey);
    }

    public int countPlansByBatchWithStatus(String batchKey, String status) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sampling_plan WHERE batch_key = ? AND status = ?",
                Integer.class, batchKey, status);
        return count == null ? 0 : count;
    }

    public void insertPlan(PlanRow row) {
        jdbc.update("INSERT INTO sampling_plan (plan_key, batch_key, seq, sample_size,"
                        + " accept_number, reject_number, basis, status, weighted_defects,"
                        + " registered_count, created_at, decided_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.planKey(), row.batchKey(), row.seq(), row.sampleSize(),
                row.acceptNumber(), row.rejectNumber(), row.basis(), row.status(),
                row.weightedDefects(), row.registeredCount(), row.createdAt(), row.decidedAt());
    }

    /**
     * 登记落定后原子更新计划累计值与判定状态；decidedAt 为 null 时写 NULL（仍保持 OPEN）。
     */
    public void applyRegistration(String planKey, int weightedDefects, int registeredCount,
                                  String status, String decidedAt) {
        jdbc.update("UPDATE sampling_plan SET weighted_defects = ?, registered_count = ?,"
                        + " status = ?, decided_at = ? WHERE plan_key = ?",
                weightedDefects, registeredCount, status, decidedAt, planKey);
    }

    public Optional<SampleRecordRow> findSampleRecord(String planKey, int sampleIndex) {
        return jdbc.query("SELECT * FROM sample_record WHERE plan_key = ? AND sample_index = ?",
                        SAMPLE_MAPPER, planKey, sampleIndex)
                .stream().findFirst();
    }

    public void insertSampleRecord(SampleRecordRow row) {
        jdbc.update("INSERT INTO sample_record (plan_key, sample_index, result, description,"
                        + " weight, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.planKey(), row.sampleIndex(), row.result(), row.description(),
                row.weight(), row.createdAt());
    }

    public List<SampleRecordRow> findSampleRecords(String planKey) {
        return jdbc.query("SELECT * FROM sample_record WHERE plan_key = ? ORDER BY sample_index",
                SAMPLE_MAPPER, planKey);
    }

    /**
     * 按登记提交顺序（id 升序）返回逐件结果，用于重放每件落定瞬间的状态快照。
     */
    public List<SampleRecordRow> findSampleRecordsInCommitOrder(String planKey) {
        return jdbc.query("SELECT * FROM sample_record WHERE plan_key = ? ORDER BY id",
                SAMPLE_MAPPER, planKey);
    }
}
