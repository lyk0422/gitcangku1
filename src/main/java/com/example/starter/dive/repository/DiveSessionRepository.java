package com.example.starter.dive.repository;

import com.example.starter.dive.domain.DiveSession;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 潜水作业仓储，基于JdbcTemplate，SQL全部参数绑定。
 */
@Repository
public class DiveSessionRepository {

    private static final String COLUMNS =
            "id, name, planned_start_utc, planned_end_utc, max_depth_m, max_duration_min, "
                    + "point_version, created_at_utc";

    private final JdbcTemplate jdbc;

    public DiveSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按ID查询作业。 */
    public Optional<DiveSession> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM dive_session WHERE id = ?", new SessionMapper(), id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 按ID查询并锁定作业行（SELECT ... FOR UPDATE），用于追加点位与审核的串行化。 */
    public Optional<DiveSession> lockById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM dive_session WHERE id = ? FOR UPDATE",
                    new SessionMapper(), id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 更新点位版本号。 */
    public void updatePointVersion(long id, int pointVersion) {
        jdbc.update("UPDATE dive_session SET point_version = ? WHERE id = ?", pointVersion, id);
    }

    private static final class SessionMapper implements RowMapper<DiveSession> {
        @Override
        public DiveSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new DiveSession(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getObject("planned_start_utc", OffsetDateTime.class).toInstant(),
                    rs.getObject("planned_end_utc", OffsetDateTime.class).toInstant(),
                    rs.getBigDecimal("max_depth_m"),
                    rs.getInt("max_duration_min"),
                    rs.getInt("point_version"),
                    rs.getObject("created_at_utc", OffsetDateTime.class).toInstant());
        }
    }
}