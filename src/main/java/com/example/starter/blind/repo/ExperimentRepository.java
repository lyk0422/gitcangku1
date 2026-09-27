package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 实验与席位数据访问；席位处理映射仅落库，不输出到日志。
 * seat_no 为区内跨版本连续序号：版本1固定1~4，后继版本从既有最大序号之后顺延。
 */
@Repository
public class ExperimentRepository {

    /** 实验行。status 取值 OPEN/CLOSED；createdAt 为 Unix 毫秒 UTC。 */
    public record ExperimentRow(String id, int blockCount, String status, long createdAt) {
    }

    /** 席位行（盲底）：treatment 为 A/B，seatNo 可直接解码，禁止出现在普通接口。 */
    public record SeatRow(String experimentId, int blockNo, int versionNo, int seatNo, String treatment) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getLong("created_at"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("version_no"),
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
     * 行级锁定实验，串行化同实验的分配/封存/扩容并发；事务结束时释放。
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
        jdbc.update("INSERT INTO seat (experiment_id, block_no, version_no, seat_no, treatment) "
                        + "VALUES (?, ?, ?, ?, ?)",
                seat.experimentId(), seat.blockNo(), seat.versionNo(), seat.seatNo(), seat.treatment());
    }

    public List<SeatRow> findSeats(String experimentId) {
        return jdbc.query(
                "SELECT experiment_id, block_no, version_no, seat_no, treatment FROM seat "
                        + "WHERE experiment_id = ? ORDER BY block_no, version_no, seat_no",
                SEAT_MAPPER, experimentId);
    }

    /**
     * 按区内跨版本连续序号读取席位（揭盲固化历史用）；seat_no 在区内全局唯一。
     */
    public SeatRow findSeat(String experimentId, int blockNo, int seatNo) {
        List<SeatRow> rows = jdbc.query(
                "SELECT experiment_id, block_no, version_no, seat_no, treatment FROM seat "
                        + "WHERE experiment_id = ? AND block_no = ? AND seat_no = ?",
                SEAT_MAPPER, experimentId, blockNo, seatNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 读取指定版本的全部席位，按区内序号升序。
     */
    public List<SeatRow> findSeatsByVersion(String experimentId, int blockNo, int versionNo) {
        return jdbc.query(
                "SELECT experiment_id, block_no, version_no, seat_no, treatment FROM seat "
                        + "WHERE experiment_id = ? AND block_no = ? AND version_no = ? "
                        + "ORDER BY seat_no",
                SEAT_MAPPER, experimentId, blockNo, versionNo);
    }

    /**
     * 区组内已存在的最大区内序号；无席位时返回 0。
     */
    public int findMaxSeatNo(String experimentId, int blockNo) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(seat_no) FROM seat WHERE experiment_id = ? AND block_no = ?",
                Integer.class, experimentId, blockNo);
        return max == null ? 0 : max;
    }
}
