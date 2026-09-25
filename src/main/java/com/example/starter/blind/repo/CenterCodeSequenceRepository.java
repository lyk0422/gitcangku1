package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 中心独立盲码序列数据访问；序列内容（盲码与处理映射）仅落库，不进入日志与普通视图。
 * 发放按中心×当前协议版本内 seq_no 顺序原子领取。
 */
@Repository
public class CenterCodeSequenceRepository {

    /** 序列行（含盲底 treatment，仅供服务层内部使用）。 */
    public record SequenceRow(
            long id,
            String experimentId,
            String centerId,
            int versionNo,
            int seqNo,
            String blindCode,
            String treatment,
            String status,
            Long allocationId) {
    }

    /** 中心×版本序列计数（不含盲底内容），用于查询与剩余容量校验。 */
    public record SequenceCount(String centerId, int versionNo, long total, long reserved, long issued) {
    }

    private static final RowMapper<SequenceRow> MAPPER = (rs, n) -> new SequenceRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("center_id"),
            rs.getInt("version_no"),
            rs.getInt("seq_no"),
            rs.getString("blind_code"),
            rs.getString("treatment"),
            rs.getString("status"),
            (Long) rs.getObject("allocation_id"));

    private static final String COLUMNS =
            "id, experiment_id, center_id, version_no, seq_no, blind_code, treatment, "
                    + "status, allocation_id";

    private final JdbcTemplate jdbc;

    public CenterCodeSequenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SequenceRow row) {
        jdbc.update("INSERT INTO center_code_sequence ("
                        + "experiment_id, center_id, version_no, seq_no, blind_code, "
                        + "treatment, status, allocation_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, 'RESERVED', NULL)",
                row.experimentId(), row.centerId(), row.versionNo(), row.seqNo(),
                row.blindCode(), row.treatment());
    }

    /**
     * 行级锁定并返回中心×版本内第一条未发放序列；并发登记时串行领取，不重发。
     */
    public SequenceRow lockFirstReserved(String experimentId, String centerId, int versionNo) {
        List<SequenceRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM center_code_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version_no = ? "
                        + "AND status = 'RESERVED' ORDER BY seq_no LIMIT 1 FOR UPDATE",
                MAPPER, experimentId, centerId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 发放：仅 RESERVED 可置 ISSUED 并绑定分配。
     *
     * @return 受影响行数；0 表示不存在或已被并发发放
     */
    public int markIssued(long sequenceId, long allocationId) {
        return jdbc.update(
                "UPDATE center_code_sequence SET status = 'ISSUED', allocation_id = ? "
                        + "WHERE id = ? AND status = 'RESERVED'",
                allocationId, sequenceId);
    }

    /** 中心×版本已预留序列总数。 */
    public long countByVersion(String experimentId, String centerId, int versionNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version_no = ?",
                Long.class, experimentId, centerId, versionNo);
        return count == null ? 0 : count;
    }

    /** 中心×版本尚未发放（RESERVED）序列数。 */
    public long countReserved(String experimentId, String centerId, int versionNo) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM center_code_sequence "
                        + "WHERE experiment_id = ? AND center_id = ? AND version_no = ? "
                        + "AND status = 'RESERVED'",
                Long.class, experimentId, centerId, versionNo);
        return count == null ? 0 : count;
    }

    /** 实验下全部中心×版本的序列计数（按中心、版本排序），不含盲码与处理代码。 */
    public List<SequenceCount> summarize(String experimentId) {
        return jdbc.query(
                "SELECT center_id, version_no, COUNT(*) AS total, "
                        + "SUM(CASE WHEN status = 'RESERVED' THEN 1 ELSE 0 END) AS reserved, "
                        + "SUM(CASE WHEN status = 'ISSUED' THEN 1 ELSE 0 END) AS issued "
                        + "FROM center_code_sequence WHERE experiment_id = ? "
                        + "GROUP BY center_id, version_no ORDER BY center_id, version_no",
                (rs, n) -> new SequenceCount(
                        rs.getString("center_id"),
                        rs.getInt("version_no"),
                        rs.getLong("total"),
                        rs.getLong("reserved"),
                        rs.getLong("issued")),
                experimentId);
    }
}
