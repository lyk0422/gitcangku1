package com.example.starter.repo;

import com.example.starter.model.PilotRow;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 引航员表访问。
 */
@Repository
public class PilotRepository {

    private static final RowMapper<PilotRow> MAPPER = (rs, rowNum) -> new PilotRow(
            rs.getLong("id"), rs.getString("code"), rs.getString("name"),
            rs.getInt("qualification_level"));

    private final JdbcTemplate jdbc;

    public PilotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PilotRow> findByCode(String code) {
        return jdbc.query("SELECT * FROM pilot WHERE code = ?", MAPPER, code)
                .stream().findFirst();
    }

    public Optional<PilotRow> findById(long id) {
        return jdbc.query("SELECT * FROM pilot WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    /**
     * 对引航员行加写锁，串行化同一引航员的并发指派。
     */
    public void lockById(long id) {
        jdbc.query("SELECT id FROM pilot WHERE id = ? FOR UPDATE",
                (rs, rowNum) -> rs.getLong(1), id);
    }
}