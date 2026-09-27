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
    public record ExperimentRow(String id, int blockCount, String status, long createdAt) {
    }

    /** 席位行（盲底）：treatment 为 A/B，seatNo 可直接解码，禁止出现在普通接口。 */
    public record SeatRow(String experimentId, int blockNo, int seatNo, String treatment) {
    }

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, n) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getLong("created_at"));

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, n) -> new SeatRow(
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

    /**
     * 扩容：仅允许追加更大区组号，总区组数受 ck_experiment_block_count(2~8) 兜底。
     *
     * @return 受影响行数；0 表示实验不存在
     */
    public int updateBlockCount(String experimentId, int newBlockCount) {
        return jdbc.update("UPDATE experiment SET block_count = ? WHERE id = ?",
                newBlockCount, experimentId);
    }

    /** 实验当前包含的处理代码集合（去重升序）。 */
    public List<String> findTreatments(String experimentId) {
        return jdbc.queryForList(
                "SELECT DISTINCT treatment FROM seat WHERE experiment_id = ? ORDER BY treatment",
                String.class, experimentId);
    }

    /** 指定代码的席位总数（含已分配与已封存）。 */
    public long countSeatsByTreatment(String experimentId, String treatment) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat WHERE experiment_id = ? AND treatment = ?",
                Long.class, experimentId, treatment);
        return count == null ? 0 : count;
    }

    /**
     * 条件封存单个席位：仅 ACTIVE 可封存，保证同一席位不能同时被分配和封存。
     * 调用方须持有实验行锁，并核对受影响行数为 1，否则整批回滚。
     *
     * @return 受影响行数；0 表示席位不存在或已被封存
     */
    public int sealSeat(String experimentId, int blockNo, int seatNo,
                        String retirementId, long sealedAt) {
        return jdbc.update(
                "UPDATE seat SET status = 'SEALED', sealed_by_retirement_id = ?, sealed_at = ? "
                        + "WHERE experiment_id = ? AND block_no = ? AND seat_no = ? "
                        + "AND status = 'ACTIVE'",
                retirementId, sealedAt, experimentId, blockNo, seatNo);
    }

    /** 区组名额统计行：available 为 ACTIVE 且未分配的席位数。 */
    public record BlockCapacityStat(int blockNo, int totalSeats, int occupiedSeats,
                                    int sealedSeats, int availableSeats) {
    }

    /** 代码配额统计行：available 为 ACTIVE 且未分配的席位数。 */
    public record TreatmentCapacityStat(String treatment, int totalSeats, int allocatedCount,
                                        int sealedCount, int availableSeats) {
    }

    /** 按区组统计名额：总数、已分配、已封存、仍可分配；按区组号升序。 */
    public List<BlockCapacityStat> blockCapacityStats(String experimentId) {
        return jdbc.query(
                "SELECT s.block_no, COUNT(*) AS total, "
                        + "SUM(CASE WHEN a.id IS NOT NULL THEN 1 ELSE 0 END) AS occupied, "
                        + "SUM(CASE WHEN s.status = 'SEALED' THEN 1 ELSE 0 END) AS sealed, "
                        + "SUM(CASE WHEN s.status = 'ACTIVE' AND a.id IS NULL THEN 1 ELSE 0 END) "
                        + "AS available "
                        + "FROM seat s LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? "
                        + "GROUP BY s.block_no ORDER BY s.block_no",
                (rs, n) -> new BlockCapacityStat(
                        rs.getInt("block_no"),
                        rs.getInt("total"),
                        rs.getInt("occupied"),
                        rs.getInt("sealed"),
                        rs.getInt("available")),
                experimentId);
    }

    /** 按处理代码统计配额：总数、已分配、已封存、仍可分配；按代码升序。 */
    public List<TreatmentCapacityStat> treatmentCapacityStats(String experimentId) {
        return jdbc.query(
                "SELECT s.treatment, COUNT(*) AS total, "
                        + "SUM(CASE WHEN a.id IS NOT NULL THEN 1 ELSE 0 END) AS allocated, "
                        + "SUM(CASE WHEN s.status = 'SEALED' THEN 1 ELSE 0 END) AS sealed, "
                        + "SUM(CASE WHEN s.status = 'ACTIVE' AND a.id IS NULL THEN 1 ELSE 0 END) "
                        + "AS available "
                        + "FROM seat s LEFT JOIN allocation a "
                        + "ON a.experiment_id = s.experiment_id "
                        + "AND a.block_no = s.block_no AND a.seat_no = s.seat_no "
                        + "WHERE s.experiment_id = ? "
                        + "GROUP BY s.treatment ORDER BY s.treatment",
                (rs, n) -> new TreatmentCapacityStat(
                        rs.getString("treatment"),
                        rs.getInt("total"),
                        rs.getInt("allocated"),
                        rs.getInt("sealed"),
                        rs.getInt("available")),
                experimentId);
    }
}
