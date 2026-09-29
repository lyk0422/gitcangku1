package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 不确定度分量持久化。预算按 (measurement_id, budget_version) 整体追加写入，历史版本只增不改；
 * 名称在同一测量同一预算版本内唯一（DB 约束兜底）。
 */
@Repository
public class UncertaintyComponentRepository {

    private static final RowMapper<UncertaintyComponent> MAPPER = (rs, rowNum) -> new UncertaintyComponent(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getInt("budget_version"),
            rs.getString("name"),
            rs.getBigDecimal("magnitude"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public UncertaintyComponentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一个分量行；同预算版本名称重复时由唯一约束拒绝。
     */
    public long insert(long measurementId, int budgetVersion, String name,
                       java.math.BigDecimal magnitude, java.time.Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO uncertainty_component "
                            + "(measurement_id, budget_version, name, magnitude, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, measurementId);
            ps.setInt(2, budgetVersion);
            ps.setString(3, name);
            ps.setBigDecimal(4, magnitude);
            ps.setObject(5, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 读取某测量某预算版本的全部分量，按名称升序（规范化集合，保证放行读取一致预算）。
     */
    public List<UncertaintyComponent> findByMeasurementAndVersion(long measurementId, int budgetVersion) {
        return jdbc.query(
                "SELECT * FROM uncertainty_component WHERE measurement_id = ? AND budget_version = ? "
                        + "ORDER BY name ASC, id ASC",
                MAPPER, measurementId, budgetVersion);
    }
}
