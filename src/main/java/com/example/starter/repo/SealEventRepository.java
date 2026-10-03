package com.example.starter.repo;

import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.domain.SealEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 封签状态记录仓储：只追加不删除。
 */
@Repository
public class SealEventRepository {

    private static final RowMapper<SealEvent> MAPPER = (rs, n) -> new SealEvent(
            rs.getLong("id"), rs.getLong("package_id"), rs.getString("event_type"), rs.getString("seal_no"),
            rs.getString("note"), rs.getTimestamp("recorded_at").toInstant());

    private final JdbcTemplate jdbc;

    public SealEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long packageId, String eventType, String sealNo, String note, Instant recordedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO seal_event (package_id, event_type, seal_no, note, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, packageId);
            ps.setString(2, eventType);
            ps.setString(3, sealNo);
            ps.setString(4, note);
            ps.setTimestamp(5, Timestamp.from(recordedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<SealEvent> listByPackageId(long packageId) {
        return jdbc.query("SELECT * FROM seal_event WHERE package_id = ? ORDER BY id", MAPPER, packageId);
    }

    public Optional<Long> findLatestEventId(long packageId, String eventType) {
        return jdbc.query("SELECT id FROM seal_event WHERE package_id = ? AND event_type = ? ORDER BY id DESC",
                (rs, n) -> rs.getLong("id"), packageId, eventType).stream().findFirst();
    }
}