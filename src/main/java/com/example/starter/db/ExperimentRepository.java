package com.example.starter.db;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 实验与固定席位数据访问。 */
@Repository
public class ExperimentRepository {

    private static final RowMapper<ExperimentRow> EXPERIMENT_MAPPER = (rs, rowNum) -> new ExperimentRow(
            rs.getString("id"),
            rs.getInt("block_count"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SeatRow> SEAT_MAPPER = (rs, rowNum) -> new SeatRow(
            rs.getString("experiment_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("treatment_code"));

    /** 每个区组按席位提交顺序固定两个 A 与两个 B。 */
    private static final String[] BLOCK_TREATMENTS = {"A", "A", "B", "B"};

    private final JdbcTemplate jdbc;

    public ExperimentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按编号查询实验。 */
    public Optional<ExperimentRow> findById(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT id, block_count, status, created_at FROM experiment WHERE id = ?",
                EXPERIMENT_MAPPER, experimentId);
        return rows.stream().findFirst();
    }

    /** 按编号查询实验并对该行加写锁（同实验写操作串行化）。 */
    public Optional<ExperimentRow> findByIdForUpdate(String experimentId) {
        List<ExperimentRow> rows = jdbc.query(
                "SELECT id, block_count, status, created_at FROM experiment WHERE id = ? FOR UPDATE",
                EXPERIMENT_MAPPER, experimentId);
        return rows.stream().findFirst();
    }

    /** 插入实验，编号冲突时抛出重复键异常。 */
    public void insert(ExperimentRow row) {
        jdbc.update(
                "INSERT INTO experiment (id, block_count, status, created_at) VALUES (?, ?, ?, ?)",
                row.id(), row.blockCount(), row.status(), Timestamp.from(row.createdAt()));
    }

    /** 批量写入固定席位：每个区组 4 席，顺序 A、A、B、B。 */
    public void insertSeats(String experimentId, int blockCount) {
        record SeatPosition(int blockNo, int seatNo, String treatment) {
        }
        java.util.List<SeatPosition> positions = new java.util.ArrayList<>(blockCount * 4);
        for (int blockNo = 1; blockNo <= blockCount; blockNo++) {
            for (int seatNo = 1; seatNo <= 4; seatNo++) {
                positions.add(new SeatPosition(blockNo, seatNo, BLOCK_TREATMENTS[seatNo - 1]));
            }
        }
        jdbc.batchUpdate(
                "INSERT INTO experiment_seat (experiment_id, block_no, seat_no, treatment_code) "
                        + "VALUES (?, ?, ?, ?)",
                positions,
                positions.size(),
                (PreparedStatement ps, SeatPosition p) -> {
                    ps.setString(1, experimentId);
                    ps.setInt(2, p.blockNo());
                    ps.setInt(3, p.seatNo());
                    ps.setString(4, p.treatment());
                });
    }

    /** 查询按区组、席位顺序排列的第一个空席位（退组席位仍视为占用）。 */
    public Optional<SeatRow> findFirstFreeSeat(String experimentId) {
        List<SeatRow> rows = jdbc.query(
                "SELECT s.experiment_id, s.block_no, s.seat_no, s.treatment_code "
                        + "FROM experiment_seat s "
                        + "WHERE s.experiment_id = ? "
                        + "AND NOT EXISTS ("
                        + "    SELECT 1 FROM allocation a "
                        + "    WHERE a.experiment_id = s.experiment_id "
                        + "    AND a.block_no = s.block_no AND a.seat_no = s.seat_no) "
                        + "ORDER BY s.block_no ASC, s.seat_no ASC "
                        + "LIMIT 1",
                SEAT_MAPPER, experimentId);
        return rows.stream().findFirst();
    }

    /** 条件关闭：仅 OPEN 时生效，返回是否更新成功。 */
    public boolean closeIfOpen(String experimentId) {
        return jdbc.update(
                "UPDATE experiment SET status = 'CLOSED' WHERE id = ? AND status = 'OPEN'",
                experimentId) > 0;
    }

    /** 查询实验下全部席位，按区组、席位顺序排列（测试断言用）。 */
    public List<SeatRow> findAllSeats(String experimentId) {
        return jdbc.query(
                "SELECT experiment_id, block_no, seat_no, treatment_code "
                        + "FROM experiment_seat WHERE experiment_id = ? "
                        + "ORDER BY block_no ASC, seat_no ASC",
                SEAT_MAPPER, experimentId);
    }
}
