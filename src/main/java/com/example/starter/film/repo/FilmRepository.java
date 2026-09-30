package com.example.starter.film.repo;

import com.example.starter.film.model.Film;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 影片表数据访问。
 */
@Repository
public class FilmRepository {

    private static final RowMapper<Film> MAPPER = (rs, n) -> new Film(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("title"),
            rs.getInt("planned_segments"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final NamedParameterJdbcTemplate jdbc;

    public FilmRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入影片并回填自增ID。
     */
    public Film insert(String code, String title, int plannedSegments, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                "INSERT INTO film(code, title, planned_segments, created_at) "
                        + "VALUES (:code, :title, :plannedSegments, :createdAt)",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("title", title)
                        .addValue("plannedSegments", plannedSegments)
                        .addValue("createdAt", Timestamp.valueOf(now)),
                keyHolder,
                new String[]{"id"}
        );
        long id = keyHolder.getKey().longValue();
        return new Film(id, code, title, plannedSegments, now);
    }

    public Optional<Film> findById(long filmId) {
        List<Film> list = jdbc.query(
                "SELECT id, code, title, planned_segments, created_at FROM film WHERE id = :id",
                new MapSqlParameterSource("id", filmId),
                MAPPER
        );
        return list.stream().findFirst();
    }

    /**
     * SELECT ... FOR UPDATE：在事务内锁定影片行，串行化该片的片段提交与整片审核。
     */
    public Optional<Film> lockById(long filmId) {
        List<Film> list = jdbc.query(
                "SELECT id, code, title, planned_segments, created_at FROM film WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", filmId),
                MAPPER
        );
        return list.stream().findFirst();
    }
}
