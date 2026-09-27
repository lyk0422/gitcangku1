package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 随机表版本数据访问；封存记录只保存表摘要、容量、处理代码集合与时刻，
 * 不保存具体序列或封存密钥。
 */
@Repository
public class RandomTableVersionRepository {

    /**
     * 随机表版本行。不含 sealKey 与具体序列。
     */
    public record RandomTableVersionRow(
            String experimentId,
            int blockNo,
            int versionNo,
            String tableDigest,
            int seatFrom,
            int seatTo,
            int addedSeats,
            String treatmentCodes,
            Integer predecessorVersion,
            String status,
            long sealedAt,
            Long closedAt) {
    }

    private static final RowMapper<RandomTableVersionRow> MAPPER =
            (rs, n) -> new RandomTableVersionRow(
                    rs.getString("experiment_id"),
                    rs.getInt("block_no"),
                    rs.getInt("version_no"),
                    rs.getString("table_digest"),
                    rs.getInt("seat_from"),
                    rs.getInt("seat_to"),
                    rs.getInt("added_seats"),
                    rs.getString("treatment_codes"),
                    (Integer) rs.getObject("predecessor_version"),
                    rs.getString("status"),
                    rs.getLong("sealed_at"),
                    (Long) rs.getObject("closed_at"));

    private static final String COLUMNS =
            "experiment_id, block_no, version_no, table_digest, seat_from, seat_to, "
                    + "added_seats, treatment_codes, predecessor_version, status, sealed_at, closed_at";

    private final JdbcTemplate jdbc;

    public RandomTableVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入新版本；同区组同版本号重复时由主键触发 DuplicateKeyException。
     */
    public void insert(RandomTableVersionRow row) {
        jdbc.update("INSERT INTO random_table_version ("
                        + "experiment_id, block_no, version_no, table_digest, "
                        + "seat_from, seat_to, added_seats, treatment_codes, "
                        + "predecessor_version, status, sealed_at, closed_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.blockNo(), row.versionNo(), row.tableDigest(),
                row.seatFrom(), row.seatTo(), row.addedSeats(), row.treatmentCodes(),
                row.predecessorVersion(), row.status(), row.sealedAt(), row.closedAt());
    }

    /**
     * 按版本号升序返回区组全部版本（历史查询）。
     */
    public List<RandomTableVersionRow> findByBlock(String experimentId, int blockNo) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? ORDER BY version_no",
                MAPPER, experimentId, blockNo);
    }

    /**
     * 返回区组当前最新版本；未封存时返回 null。
     */
    public RandomTableVersionRow findLatest(String experimentId, int blockNo) {
        List<RandomTableVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? "
                        + "ORDER BY version_no DESC LIMIT 1",
                MAPPER, experimentId, blockNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public RandomTableVersionRow findVersion(String experimentId, int blockNo, int versionNo) {
        List<RandomTableVersionRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM random_table_version "
                        + "WHERE experiment_id = ? AND block_no = ? AND version_no = ?",
                MAPPER, experimentId, blockNo, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 关闭未分配余量：仅 SEALED -> CLOSED，记录关闭时刻；不可撤销。
     *
     * @return 受影响行数；0 表示不存在或已关闭
     */
    public int closeRemainder(String experimentId, int blockNo, int versionNo, long closedAt) {
        return jdbc.update(
                "UPDATE random_table_version SET status = 'CLOSED', closed_at = ? "
                        + "WHERE experiment_id = ? AND block_no = ? AND version_no = ? "
                        + "AND status = 'SEALED'",
                closedAt, experimentId, blockNo, versionNo);
    }
}
