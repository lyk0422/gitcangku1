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

    /** 实验行。status 取值 OPEN/CLOSED；version 为乐观版本号；createdAt 为 Unix 毫秒 UTC。 */
    public record ExperimentRow(String id, int blockCount, String status, String ownerActor,
                                int version, long createdAt) {
    }

    /** 席位行（盲底）：treatment 为 A/B，seatNo 可直接解码，禁止出现在普通接口。 */
    public record SeatRow(String experimentId, int blockNo, int seatNo, String treatment) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getString("owner_actor"),
            rs.getInt("version"),
            rs.getLong("created_at"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("treatment"));

    private static final String EXPERIMENT_COLUMNS =
            "id, block_count, status, owner_actor, version, created_at";

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertExperiment(ExperimentRow row) {
        jdbc.update("INSERT INTO experiment (id, block_count, status, owner_actor, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.id(), row.blockCount(), row.status(), row.ownerActor(), row.version(),
                row.createdAt());
    }

    public ExperimentRow findById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT " + EXPERIMENT_COLUMNS + " FROM experiment WHERE id = ?",
                EXPERIMENT_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁定实验，串行化同实验的分配、揭盲批准、数据提交与职责轮换并发；事务结束时释放。
     */
    public ExperimentRow lockById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT " + EXPERIMENT_COLUMNS + " FROM experiment WHERE id = ? FOR UPDATE",
                EXPERIMENT_MAPPER, experimentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 关闭实验并递增版本；仅 OPEN 时生效，返回受影响行数。
     */
    public int markClosed(String experimentId) {
        return jdbc.update("UPDATE experiment SET status = 'CLOSED', version = version + 1 "
                + "WHERE id = ? AND status = 'OPEN'", experimentId);
    }

    /**
     * 递增实验乐观版本号；调用方须已持有实验行锁，使版本变化与业务变更同事务可见。
     */
    public void incrementVersion(String experimentId) {
        jdbc.update("UPDATE experiment SET version = version + 1 WHERE id = ?", experimentId);
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
