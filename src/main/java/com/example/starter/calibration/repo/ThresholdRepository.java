package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyThreshold;

/**
 * 仪器不确定度阈值持久化。阈值版本化、只增不改：发布即新版本，版本号在仪器锁内分配。
 */
@Repository
public class ThresholdRepository {

    private static final RowMapper<UncertaintyThreshold> MAPPER = (rs, rowNum) -> new UncertaintyThreshold(
            rs.getLong("id"),
            rs.getString("instrument_id"),
            rs.getInt("version"),
            rs.getBigDecimal("max_uncertainty"),
            rs.getString("created_by"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ThresholdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 发布新版本并返回新行 ID；(instrument_id, version) 唯一约束兜底版本号竞争。
     */
    public long insert(String instrumentId, int version, BigDecimal maxUncertainty,
                       String createdBy, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_threshold "
                            + "(instrument_id, version, max_uncertainty, created_by, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, instrumentId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, maxUncertainty);
            ps.setString(4, createdBy);
            ps.setObject(5, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询仪器当前（版本号最大）阈值版本；从未发布时为空。
     */
    public Optional<UncertaintyThreshold> findCurrent(String instrumentId) {
        return jdbc.query(
                "SELECT * FROM uncertainty_threshold WHERE instrument_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                MAPPER, instrumentId).stream().findFirst();
    }

    /**
     * 按行 ID 查询阈值版本（不加锁），用于加载已固化快照引用。
     */
    public Optional<UncertaintyThreshold> findById(long id) {
        return jdbc.query("SELECT * FROM uncertainty_threshold WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 当前最大版本号；无版本时返回 0。须在持有仪器锁的事务内调用以串行化发布。
     */
    public int currentVersionNumber(String instrumentId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM uncertainty_threshold WHERE instrument_id = ?",
                Integer.class, instrumentId);
        return v == null ? 0 : v;
    }
}
