package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心独立盲码序列数据访问；按中心+协议版本预留，顺序消耗。
 * 既有序列永远归属生成时的协议版本，修订不改动旧版本序列。
 */
@Repository
public class CenterSequenceRepository {

    /** 序列条目行。allocationId 为 null 表示该盲码尚未使用。 */
    public record SequenceRow(
            long id,
            String experimentId,
            String centerId,
            int version,
            int seqNo,
            String blindCode,
            Long allocationId,
            Long consumedAt) {
    }

    private static final RowMapper<SequenceRow> MAPPER = (rs, n) -> new SequenceRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("center_id"),
            rs.getInt("version"),
            rs.getInt("seq_no"),
            rs.getString("blind_code"),
            (Long) rs.getObject("allocation_id"),
            (Long) rs.getObject("consumed_at"));

    private static final String COLUMNS =
            "id, experiment_id, center_id, version, seq_no, blind_code, allocation_id, consumed_at";

    private final JdbcTemplate jdbc;

    public CenterSequenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String experimentId, String centerId, int version, int seqNo, String blindCode) {
        jdbc.update(
                "INSERT INTO center_sequence "
                        + "(experiment_id, center_id, version, seq_no, blind_code) "
                        + "VALUES (?, ?, ?, ?, ?)",
                experimentId, centerId, version, seqNo, blindCode);
    }

    /**
     * 原子领取该中心+版本内第一个未消耗盲码：加行级锁并跳过已消耗项，按 seq_no 取最小一条。
     *
     * @return 领取到的序列条目；该序列耗尽时返回 null
     */
    public SequenceRow takeFirstAvailable(String experimentId, String centerId, int version) {
        List<SequenceRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version = ? "
                        + "AND allocation_id IS NULL "
                        + "ORDER BY seq_no LIMIT 1 FOR UPDATE",
                MAPPER, experimentId, centerId, version);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 消耗盲码：仅未使用条目可标记。
     *
     * @return 受影响行数；0 表示不存在或已被并发消耗
     */
    public int consume(long sequenceId, long allocationId, long consumedAt) {
        return jdbc.update(
                "UPDATE center_sequence SET allocation_id = ?, consumed_at = ? "
                        + "WHERE id = ? AND allocation_id IS NULL",
                allocationId, consumedAt, sequenceId);
    }

    public long countTotal(String experimentId, String centerId, int version) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version = ?",
                Long.class, experimentId, centerId, version);
        return count == null ? 0 : count;
    }

    public long countConsumed(String experimentId, String centerId, int version) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version = ? "
                        + "AND allocation_id IS NOT NULL",
                Long.class, experimentId, centerId, version);
        return count == null ? 0 : count;
    }
}
