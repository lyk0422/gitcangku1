package com.example.starter.repo;

import com.example.starter.domain.Inflorescence;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 花序表数据访问。
 */
@Repository
public class InflorescenceRepository {

    private static final RowMapper<Inflorescence> MAPPER = (rs, rowNum) -> new Inflorescence(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getTimestamp("first_open_at").toInstant(),
            Optional.ofNullable(rs.getTimestamp("removed_at")).map(Timestamp::toInstant).orElse(null),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public InflorescenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建花序，返回含自增主键的完整记录。 */
    public Inflorescence insert(String code, Instant firstOpenAt, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inflorescence (code, first_open_at, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setTimestamp(2, Timestamp.from(firstOpenAt));
            ps.setTimestamp(3, Timestamp.from(createdAt));
            return ps;
        }, keys);
        return new Inflorescence(keys.getKey().longValue(), code, firstOpenAt, null, createdAt);
    }

    /** 按主键查询。 */
    public Optional<Inflorescence> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM inflorescence WHERE id = ?", MAPPER, id));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /** 按主键查询并加行锁（SELECT ... FOR UPDATE），用于授粉操作串行化。 */
    public Optional<Inflorescence> findByIdForUpdate(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT * FROM inflorescence WHERE id = ? FOR UPDATE", MAPPER, id));
        } catch (EmptyResultDataAccessException empty) {
            return Optional.empty();
        }
    }

    /** 标记摘除（仅当未摘除时生效），返回受影响行数。 */
    public int markRemoved(long id, Instant removedAt) {
        return jdbc.update(
                "UPDATE inflorescence SET removed_at = ? WHERE id = ? AND removed_at IS NULL",
                Timestamp.from(removedAt), id);
    }
}