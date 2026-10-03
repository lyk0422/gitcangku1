package com.example.starter.curtailment.repo;

import com.example.starter.curtailment.domain.Instruction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 限发指令仓储：负责指令与机组锁行的 SQL 访问，所有时间参数均为 UTC 瞬时。
 */
@Repository
public class InstructionRepository {

    private static final RowMapper<Instruction> MAPPER = (rs, rowNum) -> new Instruction(
            rs.getLong("id"),
            rs.getString("instruction_no"),
            rs.getString("unit_id"),
            JdbcTimes.toInstant(rs, "start_utc"),
            JdbcTimes.toInstant(rs, "end_utc"),
            rs.getBigDecimal("max_power_kw"),
            rs.getString("status"),
            rs.getInt("version"),
            JdbcTimes.toInstant(rs, "created_at"),
            JdbcTimes.toInstant(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public InstructionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按机组加串行化锁：确保机组锁行存在后 SELECT ... FOR UPDATE 锁定，调用方须在事务内。
     *
     * @param unitId 机组编号
     * @param now    当前 UTC 时间
     */
    public void lockUnit(String unitId, Instant now) {
        try {
            jdbc.update("INSERT INTO unit_lock (unit_id, created_at) VALUES (?, ?)", unitId, JdbcTimes.toSql(now));
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // 锁行已存在，直接进入行锁等待
        }
        jdbc.queryForObject("SELECT unit_id FROM unit_lock WHERE unit_id = ? FOR UPDATE", String.class, unitId);
    }

    /**
     * 新增指令，状态固定为 PENDING、版本 0。
     *
     * @return 自增主键
     */
    public long insert(String instructionNo, String unitId, Instant startUtc, Instant endUtc,
                       java.math.BigDecimal maxPowerKw, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curtailment_instruction "
                            + "(instruction_no, unit_id, start_utc, end_utc, max_power_kw, status, version, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instructionNo);
            ps.setString(2, unitId);
            ps.setObject(3, JdbcTimes.toSql(startUtc));
            ps.setObject(4, JdbcTimes.toSql(endUtc));
            ps.setBigDecimal(5, maxPowerKw);
            ps.setObject(6, JdbcTimes.toSql(now));
            ps.setObject(7, JdbcTimes.toSql(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /** 按主键查询。 */
    public Optional<Instruction> findById(long id) {
        List<Instruction> rows = jdbc.query("SELECT * FROM curtailment_instruction WHERE id = ?", MAPPER, id);
        return rows.stream().findFirst();
    }

    /** 按主键查询并加行锁（FOR UPDATE），调用方须在事务内。 */
    public Optional<Instruction> findByIdForUpdate(long id) {
        List<Instruction> rows = jdbc.query("SELECT * FROM curtailment_instruction WHERE id = ? FOR UPDATE", MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 判断同机组是否存在与 [startUtc, endUtc) 重叠的有效（未撤销）指令。
     */
    public boolean existsActiveOverlap(String unitId, Instant startUtc, Instant endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_instruction "
                        + "WHERE unit_id = ? AND status <> 'REVOKED' AND start_utc < ? AND ? < end_utc",
                Integer.class, unitId, JdbcTimes.toSql(endUtc), JdbcTimes.toSql(startUtc));
        return count != null && count > 0;
    }

    /**
     * 条件更新：仅当当前状态在 fromStatuses 内时置为 toStatus 并递增版本，用于确认/撤销竞争。
     *
     * @return 受影响行数，0 表示状态已被并发修改
     */
    public int transitionStatus(long id, List<String> fromStatuses, String toStatus, Instant now) {
        String placeholders = String.join(", ", fromStatuses.stream().map(s -> "?").toList());
        Object[] args = new Object[fromStatuses.size() + 3];
        args[0] = toStatus;
        args[1] = JdbcTimes.toSql(now);
        args[2] = id;
        for (int i = 0; i < fromStatuses.size(); i++) {
            args[i + 3] = fromStatuses.get(i);
        }
        return jdbc.update("UPDATE curtailment_instruction SET status = ?, updated_at = ?, version = version + 1 "
                + "WHERE id = ? AND status IN (" + placeholders + ")", args);
    }

    /** 递增指令版本号（采样更正时调用）。 */
    public void bumpVersion(long id, Instant now) {
        jdbc.update("UPDATE curtailment_instruction SET version = version + 1, updated_at = ? WHERE id = ?",
                JdbcTimes.toSql(now), id);
    }
}