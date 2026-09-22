package com.example.starter.db;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 参与者分配数据访问。 */
@Repository
public class AllocationRepository {

    private static final String COLUMNS =
            "id, experiment_id, participant_id, block_no, seat_no, blind_code, status, created_at, updated_at";

    private static final RowMapper<AllocationRow> MAPPER = (rs, rowNum) -> new AllocationRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("block_no"),
            rs.getInt("seat_no"),
            rs.getString("blind_code"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcTemplate jdbc;

    public AllocationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按实验与参与者编号查询分配。 */
    public Optional<AllocationRow> find(String experimentId, String participantId) {
        return jdbc.query(
                        "SELECT " + COLUMNS + " FROM allocation "
                                + "WHERE experiment_id = ? AND participant_id = ?",
                        MAPPER, experimentId, participantId)
                .stream().findFirst();
    }

    /** 按主键查询分配。 */
    public Optional<AllocationRow> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM allocation WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /** 插入分配并回填自增主键；盲码或席位冲突由唯一约束抛出。 */
    public long insert(AllocationRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO allocation (experiment_id, participant_id, block_no, seat_no, "
                            + "blind_code, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.experimentId());
            ps.setString(2, row.participantId());
            ps.setInt(3, row.blockNo());
            ps.setInt(4, row.seatNo());
            ps.setString(5, row.blindCode());
            ps.setString(6, row.status());
            ps.setTimestamp(7, Timestamp.from(row.createdAt()));
            ps.setTimestamp(8, Timestamp.from(row.updatedAt()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("allocation insert did not return generated key");
        }
        return key.longValue();
    }

    /** 仅在 ENROLLED 状态下置为 WITHDRAWN，返回受影响行数。 */
    public int markWithdrawnIfEnrolled(long allocationId) {
        return jdbc.update(
                "UPDATE allocation SET status = 'WITHDRAWN', updated_at = ? "
                        + "WHERE id = ? AND status = 'ENROLLED'",
                Timestamp.from(java.time.Instant.now()), allocationId);
    }

    /** 判断盲码是否已被占用。 */
    public boolean blindCodeExists(String blindCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation WHERE blind_code = ?", Integer.class, blindCode);
        return count != null && count > 0;
    }

    /** 查询实验下全部分配，按领取顺序（主键）排列（测试断言用）。 */
    public List<AllocationRow> findAllByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM allocation WHERE experiment_id = ? ORDER BY id ASC",
                MAPPER, experimentId);
    }
}
