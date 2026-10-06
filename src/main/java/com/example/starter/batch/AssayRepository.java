package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 平行测定持久化层：轮次与读数；所有 SQL 参数化，十进制数值以原值字符串存取。
 */
@Repository
public class AssayRepository {

    /**
     * assay_round 表行记录；version 等于已写入读数条数，作为乐观锁版本。
     */
    public record RoundRow(long id, String batchKey, String testItem, int roundNo,
                           String meanMin, String meanMax, String maxRange,
                           int version, String status, String createdBy,
                           String createdAt, String sealedAt) {
    }

    /**
     * assay_reading 表行记录；value 为原值十进制字符串，不舍入。
     */
    public record ReadingRow(long id, long roundId, String batchKey, int seq,
                             String value, String operator, String createdAt) {
    }

    private static final RowMapper<RoundRow> ROUND_MAPPER = (rs, n) -> new RoundRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("test_item"),
            rs.getInt("round_no"), rs.getString("mean_min"), rs.getString("mean_max"),
            rs.getString("max_range"), rs.getInt("version"), rs.getString("status"),
            rs.getString("created_by"), rs.getString("created_at"), rs.getString("sealed_at"));

    private static final RowMapper<ReadingRow> READING_MAPPER = (rs, n) -> new ReadingRow(
            rs.getLong("id"), rs.getLong("round_id"), rs.getString("batch_key"),
            rs.getInt("seq"), rs.getString("reading_value"), rs.getString("operator"),
            rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public AssayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertRound(RoundRow row) {
        jdbc.update("INSERT INTO assay_round (batch_key, test_item, round_no, mean_min, mean_max,"
                        + " max_range, version, status, created_by, created_at, sealed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.testItem(), row.roundNo(), row.meanMin(), row.meanMax(),
                row.maxRange(), row.version(), row.status(), row.createdBy(),
                row.createdAt(), row.sealedAt());
    }

    /**
     * 某检验项的全部轮次，按轮次序号升序。
     */
    public List<RoundRow> findRounds(String batchKey, String testItem) {
        return jdbc.query("SELECT * FROM assay_round WHERE batch_key = ? AND test_item = ?"
                + " ORDER BY round_no", ROUND_MAPPER, batchKey, testItem);
    }

    /**
     * 某批次全部轮次，按检验项、轮次序号升序。
     */
    public List<RoundRow> findRoundsByBatch(String batchKey) {
        return jdbc.query("SELECT * FROM assay_round WHERE batch_key = ?"
                + " ORDER BY test_item, round_no", ROUND_MAPPER, batchKey);
    }

    public Optional<RoundRow> findRound(long roundId) {
        return jdbc.query("SELECT * FROM assay_round WHERE id = ?", ROUND_MAPPER, roundId)
                .stream().findFirst();
    }

    /**
     * 行锁读取轮次，串行化同一轮次上的读数写入。
     */
    public Optional<RoundRow> findRoundForUpdate(long roundId) {
        return jdbc.query("SELECT * FROM assay_round WHERE id = ? FOR UPDATE", ROUND_MAPPER, roundId)
                .stream().findFirst();
    }

    /**
     * 某检验项当前已有轮次数。
     */
    public int countRounds(String batchKey, String testItem) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM assay_round WHERE batch_key = ? AND test_item = ?",
                Integer.class, batchKey, testItem);
        return count == null ? 0 : count;
    }

    /**
     * 更新轮次版本（已写入读数条数）。
     */
    public void updateRoundVersion(long roundId, int version) {
        jdbc.update("UPDATE assay_round SET version = ? WHERE id = ?", version, roundId);
    }

    /**
     * 封结轮次：写入终态与封结时间。
     */
    public void sealRound(long roundId, String status, String sealedAt) {
        jdbc.update("UPDATE assay_round SET status = ?, sealed_at = ? WHERE id = ?",
                status, sealedAt, roundId);
    }

    public void insertReading(ReadingRow row) {
        jdbc.update("INSERT INTO assay_reading (round_id, batch_key, seq, reading_value, operator,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.roundId(), row.batchKey(), row.seq(), row.value(), row.operator(),
                row.createdAt());
    }

    /**
     * 某轮次的全部读数，按序号升序。
     */
    public List<ReadingRow> findReadings(long roundId) {
        return jdbc.query("SELECT * FROM assay_reading WHERE round_id = ? ORDER BY seq",
                READING_MAPPER, roundId);
    }

    /**
     * 某批次全部读数操作员（去重），用于批准人不得为测定操作员的校验。
     */
    public List<String> findOperators(String batchKey) {
        return jdbc.queryForList("SELECT DISTINCT operator FROM assay_reading WHERE batch_key = ?",
                String.class, batchKey);
    }
}
