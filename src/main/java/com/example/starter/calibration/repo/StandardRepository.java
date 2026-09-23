package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.Standard;

/**
 * 标准器持久化。标准器业务键全局唯一，作为血缘路径等长时按 standardId 字典序择路的依据。
 */
@Repository
public class StandardRepository {

    private static final RowMapper<Standard> MAPPER = (rs, rowNum) -> new Standard(
            rs.getLong("id"),
            rs.getString("standard_id"),
            rs.getString("name"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public StandardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建标准器；standardId 重复时抛出 DuplicateKeyException。
     */
    public long insert(String standardId, String name, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO measurement_standard (standard_id, name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, standardId);
            ps.setString(2, name);
            ps.setObject(3, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按业务键查询标准器（不加锁）。
     */
    public Optional<Standard> findByStandardId(String standardId) {
        return jdbc.query("SELECT * FROM measurement_standard WHERE standard_id = ?", MAPPER, standardId)
                .stream().findFirst();
    }

    /**
     * 查询全部标准器，按业务键字典序返回。
     */
    public List<Standard> findAll() {
        return jdbc.query("SELECT * FROM measurement_standard ORDER BY standard_id", MAPPER);
    }
}
