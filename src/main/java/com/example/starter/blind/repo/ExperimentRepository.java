package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 实验与席位数据访问；席位处理映射仅落库，不输出到日志。
 * 席位按随机表版本隔离，同区组不同版本各自从序号 1 开始。
 */
@Repository
public class ExperimentRepository {

    /** 实验行。status 取值 OPEN/CLOSED；createdAt 为 Unix 毫秒 UTC。 */
    public record ExperimentRow(String id, int blockCount, String status, long createdAt) {
    }

    /** 席位行（盲底）：treatment 为 A/B，seatNo 可直接解码，禁止出现在普通接口。 */
    public record SeatRow(long tableVersionId, String experimentId, int blockNo, int seatNo,
                          String treatment) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getLong("created_at"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
            rs.getLong("table_version_id"),
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("treatment"));

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertExperiment(ExperimentRow row) {
        jdbc.update("INSERT INTO experiment (id, block_count, status, created_at) VALUES (?, ?, ?, ?)",
                row.id(), row.blockCount(), row.status(), row.createdAt());
    }

    public ExperimentRow findById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT id, block_count, status, created_at FROM experiment WHERE id = ?",
                EXPERIMENT_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定实验，串行化同实验的分配并发；事务结束时释放。
     */
    public ExperimentRow lockById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT id, block_count, status, created_at FROM experiment WHERE id = ? FOR UPDATE",
                EXPERIMENT_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 关闭实验；仅 OPEN 时生效，返回受影响行数。
     */
    public int markClosed(String experimentId) {
        return jdbc.update("UPDATE experiment SET status = 'CLOSED' WHERE id = ? AND status = 'OPEN'",
                experimentId);
    }

    public void insertSeat(SeatRow seat) {
        jdbc.update("INSERT INTO seat (experiment_id, block_no, seat_no, treatment, table_version_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                seat.experimentId(), seat.blockNo(), seat.seatNo(), seat.treatment(),
                seat.tableVersionId());
    }

    /** 某随机表版本的全部席位，按序号升序（封存复核摘要时使用）。 */
    public List<SeatRow> findSeatsByVersion(long tableVersionId) {
        return jdbc.query(
                "SELECT table_version_id, experiment_id, block_no, seat_no, treatment FROM seat "
                        + "WHERE table_version_id = ? ORDER BY seat_no",
                SEAT_MAPPER, tableVersionId);
    }

    /** 按分配固化的版本与序号定位席位（受控揭盲时使用，不依赖可变的实验级席位顺序）。 */
    public SeatRow findSeatByVersionAndSeq(long tableVersionId, int seatNo) {
        List<SeatRow> rows = jdbc.query(
                "SELECT table_version_id, experiment_id, block_no, seat_no, treatment FROM seat "
                        + "WHERE table_version_id = ? AND seat_no = ?",
                SEAT_MAPPER, tableVersionId, seatNo);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
