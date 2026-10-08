package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 二级盲码样本数据访问；一级码/二级码全局唯一由唯一索引兜底，
 * 二级码与结果值不进入协调员视图，参与者与一级码不进入分析员视图。
 */
@Repository
public class SampleRepository {

    /**
     * 样本行（服务层内部使用）。
     * status 取值 CREATED/RECEIVED/RESULT；version 为乐观锁版本，创建为1；
     * resultValue 为整数结果，NULL=未录入；receivedAt/resultAt 为 Unix 毫秒 UTC，NULL=未发生。
     */
    public record SampleRow(
            long id,
            String experimentId,
            long allocationId,
            String primaryCode,
            String secondaryCode,
            String analystActor,
            String status,
            Integer resultValue,
            int version,
            String createdActor,
            long createdAt,
            Long receivedAt,
            Long resultAt) {
    }

    /** 闭组汇总行：有效结果份数与总和（已排除退组样本）。 */
    public record SummaryRow(long count, long sum) {
    }

    private static final RowMapper<SampleRow> MAPPER = (rs, n) -> new SampleRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getLong("allocation_id"),
            rs.getString("primary_code"),
            rs.getString("secondary_code"),
            rs.getString("analyst_actor"),
            rs.getString("status"),
            (Integer) rs.getObject("result_value"),
            rs.getInt("version"),
            rs.getString("created_actor"),
            rs.getLong("created_at"),
            (Long) rs.getObject("received_at"),
            (Long) rs.getObject("result_at"));

    private static final String COLUMNS =
            "id, experiment_id, allocation_id, primary_code, secondary_code, analyst_actor, "
                    + "status, result_value, version, created_actor, created_at, received_at, result_at";

    private final JdbcTemplate jdbc;

    public SampleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SampleRow row) {
        jdbc.update("INSERT INTO blind_sample ("
                        + "experiment_id, allocation_id, primary_code, secondary_code, analyst_actor, "
                        + "status, result_value, version, created_actor, created_at, received_at, result_at"
                        + ") VALUES (?, ?, ?, NULL, NULL, 'CREATED', NULL, ?, ?, ?, NULL, NULL)",
                row.experimentId(), row.allocationId(), row.primaryCode(),
                row.version(), row.createdActor(), row.createdAt());
    }

    public SampleRow findByAllocationId(long allocationId) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM blind_sample WHERE allocation_id = ?",
                MAPPER, allocationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SampleRow findByPrimaryCode(String primaryCode) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM blind_sample WHERE primary_code = ?",
                MAPPER, primaryCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按一级码行级锁定样本，串行化同一样本的并发接收。
     */
    public SampleRow lockByPrimaryCode(String primaryCode) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM blind_sample WHERE primary_code = ? FOR UPDATE",
                MAPPER, primaryCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public SampleRow findBySecondaryCode(String secondaryCode) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM blind_sample WHERE secondary_code = ?",
                MAPPER, secondaryCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按二级码行级锁定样本，串行化同一样本的结果写入。
     */
    public SampleRow lockBySecondaryCode(String secondaryCode) {
        List<SampleRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM blind_sample WHERE secondary_code = ? FOR UPDATE",
                MAPPER, secondaryCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 接收：仅 CREATED 可接收，写入二级码与接收分析员，版本加1。
     *
     * @return 受影响行数；0 表示不存在或已被接收
     */
    public int markReceived(long sampleId, String secondaryCode, String analystActor, long receivedAt) {
        return jdbc.update("UPDATE blind_sample SET secondary_code = ?, analyst_actor = ?, "
                        + "status = 'RECEIVED', received_at = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'CREATED'",
                secondaryCode, analystActor, receivedAt, sampleId);
    }

    /**
     * 录入结果：仅 RECEIVED 可录入，一样本仅一个结果，版本加1。
     *
     * @return 受影响行数；0 表示不存在或已录入
     */
    public int recordResult(long sampleId, int value, long resultAt) {
        return jdbc.update("UPDATE blind_sample SET result_value = ?, status = 'RESULT', "
                        + "result_at = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'RECEIVED'",
                value, resultAt, sampleId);
    }

    /**
     * 闭组汇总：统计已录入结果且分配未退组的样本份数与结果总和，不做逐样本关联。
     */
    public SummaryRow summarize(String experimentId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) AS c, COALESCE(SUM(s.result_value), 0) AS total "
                        + "FROM blind_sample s JOIN allocation a ON a.id = s.allocation_id "
                        + "WHERE s.experiment_id = ? AND s.result_value IS NOT NULL "
                        + "AND a.status = 'ASSIGNED'",
                (rs, n) -> new SummaryRow(rs.getLong("c"), rs.getLong("total")),
                experimentId);
    }

    public boolean isDuplicateAllocation(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_allocation");
    }

    public boolean isDuplicatePrimaryCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_primary_code");
    }

    public boolean isDuplicateSecondaryCode(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_sample_secondary_code");
    }
}
