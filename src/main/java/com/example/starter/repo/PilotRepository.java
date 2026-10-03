package com.example.starter.repo;

import com.example.starter.domain.Pilot;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 引航员仓储。
 */
@Repository
public class PilotRepository {

    private static final RowMapper<Pilot> MAPPER = (rs, rowNum) -> new Pilot(
            rs.getLong("id"),
            rs.getString("pilot_code"),
            rs.getString("pilot_name"),
            rs.getInt("qualification_grade"),
            rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public PilotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Pilot> findById(long id) {
        return jdbc.query("SELECT * FROM pilot WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Pilot> findByPilotCode(String pilotCode) {
        return jdbc.query("SELECT * FROM pilot WHERE pilot_code = ?", MAPPER, pilotCode).stream().findFirst();
    }

    /**
     * 按主键加行锁读取，用于串行化同一引航员的并发指派。
     */
    public Optional<Pilot> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM pilot WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
}