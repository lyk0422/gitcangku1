package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 随机表封存数据访问；只持久化摘要、容量、处理代码集合与时间，不保存具体序列。
 */
@Repository
public class RandomTableSealRepository {

    /** 封存记录行；不含任何逐序号处理代码。 */
    public record SealRow(
            String id,
            String experimentId,
            int blockNo,
            int versionNo,
            int capacity,
            String treatmentCodes,
            String tableDigest,
            String predecessorId,
            String status,
            String sealedBy,
            long sealedAt,
            Long closedAt) {
    }

    private static final RowMapper<SealRow> MAPPER = (rs, n) -> new SealRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("version_no"),
            rs.getInt("capacity"),
            rs.getString("treatment_codes"),
            rs.getString("table_digest"),
            rs.getString("predecessor_id"),
            rs.getString("status"),
            rs.getString("sealed_by"),
            rs.getLong("sealed_at"),
            (Long) rs.getObject("closed_at"));

    private static final String COLUMNS =
            "id, experiment_id, block_no, version_no, capacity, treatment_codes, table_digest, "
                    + "predecessor_id, status, sealed_by, sealed_at, closed_at";

    private final JdbcTemplate jdbc;

    public RandomTableSealRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SealRow row) {
        jdbc.update("INSERT INTO random_table_seal ("
                        + "id, experiment_id, block_no, version_no, capacity, treatment_codes, "
                        + "table_digest, predecessor_id, status, sealed_by, sealed_at, closed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                row.id(), row.experimentId(), row.blockNo(), row.versionNo(), row.capacity(),
                row.treatmentCodes(), row.tableDigest(), row.predecessorId(), row.status(),
                row.sealedBy(), row.sealedAt());
    }

    public SealRow findById(String sealId) {
        List<SealRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_seal WHERE id = ?", MAPPER, sealId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定封存记录。
     */
    public SealRow lockById(String sealId) {
        List<SealRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_seal WHERE id = ? FOR UPDATE",
                MAPPER, sealId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 区组的全部封存版本，按版本号升序。
     */
    public List<SealRow> findByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_seal "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY version_no",
                MAPPER, experimentId, blockNo);
    }

    /**
     * 实验的全部封存版本，按区组号、版本号升序。
     */
    public List<SealRow> findByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_seal "
                        + "WHERE experiment_id = ? ORDER BY block_no, version_no",
                MAPPER, experimentId);
    }

    /**
     * 关闭指定版本未分配余量：仅 SEALED 可关闭。
     *
     * @return 受影响行数；0 表示不存在或已关闭
     */
    public int markClosed(String sealId, long closedAt) {
        return jdbc.update("UPDATE random_table_seal SET status = 'CLOSED', closed_at = ? "
                + "WHERE id = ? AND status = 'SEALED'", closedAt, sealId);
    }
}
