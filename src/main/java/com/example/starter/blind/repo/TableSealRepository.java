package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 随机表封存数据访问；每个版本至多封存一次、每个 sealKey 全局唯一由唯一约束兜底。
 * 封存记录只保存摘要、容量、处理代码集合与时刻，不保存序列。
 */
@Repository
public class TableSealRepository {

    /** 封存记录行。 */
    public record TableSealRow(
            long id,
            String experimentId,
            int blockNo,
            long tableVersionId,
            String sealKey,
            String sequenceDigest,
            int blockCapacity,
            String treatmentCodes,
            long sealedAt) {
    }

    private static final String COLUMNS =
            "id, experiment_id, block_no, table_version_id, seal_key, sequence_digest, "
                    + "block_capacity, treatment_codes, sealed_at";

    private static final RowMapper<TableSealRow> MAPPER = (rs, n) -> new TableSealRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getLong("table_version_id"),
            rs.getString("seal_key"),
            rs.getString("sequence_digest"),
            rs.getInt("block_capacity"),
            rs.getString("treatment_codes"),
            rs.getLong("sealed_at"));

    private final JdbcTemplate jdbc;

    public TableSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入封存记录；版本重复封存或 sealKey 冲突由唯一约束抛出 DuplicateKeyException。
     */
    public void insert(TableSealRow row) {
        jdbc.update("INSERT INTO table_seal ("
                        + "experiment_id, block_no, table_version_id, seal_key, sequence_digest, "
                        + "block_capacity, treatment_codes, sealed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.blockNo(), row.tableVersionId(), row.sealKey(),
                row.sequenceDigest(), row.blockCapacity(), row.treatmentCodes(), row.sealedAt());
    }

    public TableSealRow findByVersionId(long tableVersionId) {
        List<TableSealRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM table_seal WHERE table_version_id = ?",
                MAPPER, tableVersionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 实验的全部封存记录（诊断查询判断区组是否已建立封存锚点）。 */
    public List<TableSealRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM table_seal WHERE experiment_id = ? ORDER BY id",
                MAPPER, experimentId);
    }

    /** 按封存密钥查找；扩容显式引用已封存版本时使用。 */
    public TableSealRow findBySealKey(String sealKey) {
        List<TableSealRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM table_seal WHERE seal_key = ?",
                MAPPER, sealKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定封存记录，防止 sealKey 并发首次插入后又被读旧。 */
    public TableSealRow lockBySealKey(String sealKey) {
        List<TableSealRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM table_seal WHERE seal_key = ? FOR UPDATE",
                MAPPER, sealKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 判断插入异常是否为版本重复封存。 */
    public boolean isDuplicateVersion(Exception e) {
        return e instanceof DuplicateKeyException
                && e.getMessage() != null && e.getMessage().contains("uq_table_seal_version");
    }

    /** 判断插入异常是否为 sealKey 全局唯一冲突。 */
    public boolean isDuplicateSealKey(Exception e) {
        return e instanceof DuplicateKeyException
                && e.getMessage() != null && e.getMessage().contains("uq_table_seal_key");
    }
}
