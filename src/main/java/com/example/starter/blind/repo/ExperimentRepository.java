package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 实验与席位数据访问；席位处理映射仅落库，不输出到日志。
 */
@Repository
public class ExperimentRepository {

    /** 实验行。status 取值 OPEN/CLOSED；createdAt 为 Unix 毫秒 UTC。 */
    public record ExperimentRow(String id, int blockCount, String status, long createdAt,
                                int roleVersion, Long activeGenerationId) {
    }

    /** 席位行（盲底）：treatment 为 A/B，seatNo 可直接解码，禁止出现在普通接口。 */
    public record SeatRow(String experimentId, int blockNo, int seatNo, String treatment) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getLong("created_at"),
            rs.getInt("role_version"),
            (Long) rs.getObject("active_generation_id"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("treatment"));

    private static final String EXPERIMENT_COLUMNS =
            "id, block_count, status, created_at, role_version, active_generation_id";

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertExperiment(ExperimentRow row) {
        jdbc.update("INSERT INTO experiment (id, block_count, status, created_at, "
                        + "role_version, active_generation_id) VALUES (?, ?, ?, ?, ?, NULL)",
                row.id(), row.blockCount(), row.status(), row.createdAt(), row.roleVersion());
    }

    public ExperimentRow findById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT " + EXPERIMENT_COLUMNS + " FROM experiment WHERE id = ?",
                EXPERIMENT_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定实验，串行化同实验的分配/关闭/轮换/令牌校验并发；事务结束时释放。
     */
    public ExperimentRow lockById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT " + EXPERIMENT_COLUMNS + " FROM experiment WHERE id = ? FOR UPDATE",
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

    /**
     * 轮换激活时在同一事务内切换活动代次指针并推进名册版本；
     * 仅当版本仍为期望值时生效（乐观锁兜底），返回受影响行数。
     */
    public int activateGeneration(String experimentId, int expectedVersion,
                                  long activeGenerationId) {
        return jdbc.update("UPDATE experiment SET role_version = ?, active_generation_id = ? "
                        + "WHERE id = ? AND role_version = ?",
                expectedVersion + 1, activeGenerationId, experimentId, expectedVersion);
    }

    public void insertSeat(SeatRow seat) {
        jdbc.update("INSERT INTO seat (experiment_id, block_no, seat_no, treatment) VALUES (?, ?, ?, ?)",
                seat.experimentId(), seat.blockNo(), seat.seatNo(), seat.treatment());
    }

    public List<SeatRow> findSeats(String experimentId) {
        return jdbc.query(
                "SELECT experiment_id, block_no, seat_no, treatment FROM seat "
                        + "WHERE experiment_id = ? ORDER BY block_no, seat_no",
                SEAT_MAPPER, experimentId);
    }

    public SeatRow findSeat(String experimentId, int blockNo, int seatNo) {
        List<SeatRow> rows = jdbc.query(
                "SELECT experiment_id, block_no, seat_no, treatment FROM seat "
                        + "WHERE experiment_id = ? AND block_no = ? AND seat_no = ?",
                SEAT_MAPPER, experimentId, blockNo, seatNo);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
