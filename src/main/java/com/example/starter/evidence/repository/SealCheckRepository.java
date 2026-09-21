package com.example.starter.evidence.repository;

import com.example.starter.evidence.domain.SealCheck;
import com.example.starter.evidence.domain.SealCheckResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 封条核验历史表访问。记录只追加，不可更新、不可删除。
 */
@Repository
public class SealCheckRepository {

    private static final RowMapper<SealCheck> MAPPER = (rs, rowNum) -> new SealCheck(
            rs.getLong("id"),
            rs.getLong("evidence_id"),
            rs.getString("actor_id"),
            SealCheckResult.valueOf(rs.getString("result")),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public SealCheckRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long evidenceId, String actorId, SealCheckResult result, String detail, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO seal_check (evidence_id, actor_id, result, detail, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, evidenceId);
            ps.setString(2, actorId);
            ps.setString(3, result.name());
            ps.setString(4, detail);
            ps.setTimestamp(5, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public List<SealCheck> findByEvidenceId(long evidenceId) {
        return jdbc.query(
                "SELECT * FROM seal_check WHERE evidence_id = ? ORDER BY id",
                MAPPER, evidenceId);
    }
}
