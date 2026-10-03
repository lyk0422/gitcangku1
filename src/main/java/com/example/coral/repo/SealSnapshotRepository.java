package com.example.coral.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.example.coral.domain.SealSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 封存快照持久化：样方ID唯一约束保证只形成一个一致的清单版本。
 */
@Repository
public class SealSnapshotRepository {

    private static final RowMapper<SealSnapshot> MAPPER = (rs, n) -> new SealSnapshot(
            rs.getLong("id"),
            rs.getLong("quadrat_id"),
            rs.getInt("alive_count"),
            rs.getInt("dead_count"),
            rs.getInt("pending_count"),
            Arrays.asList(rs.getString("plant_codes").split(",")),
            rs.getTimestamp("sealed_at").toInstant());

    private final JdbcTemplate jdbc;

    public SealSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long quadratId, int aliveCount, int deadCount, int pendingCount,
            List<String> plantCodes, Instant sealedAt) {
        var keyHolder = new GeneratedKeyHolder();
        String joined = String.join(",", plantCodes);
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO seal_snapshot (quadrat_id, alive_count, dead_count, pending_count,"
                            + " plant_codes, sealed_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, quadratId);
            ps.setInt(2, aliveCount);
            ps.setInt(3, deadCount);
            ps.setInt(4, pendingCount);
            ps.setString(5, joined);
            ps.setTimestamp(6, Timestamp.from(sealedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<SealSnapshot> findByQuadratId(long quadratId) {
        return jdbc.query("SELECT * FROM seal_snapshot WHERE quadrat_id = ?", MAPPER, quadratId)
                .stream().findFirst();
    }
}