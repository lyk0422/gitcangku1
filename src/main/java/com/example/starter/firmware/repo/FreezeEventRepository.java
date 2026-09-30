package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FreezeEvent;
import com.example.starter.firmware.domain.FreezeEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 版本冻结审计流水数据访问，只增不改。
 */
@Repository
public class FreezeEventRepository {

    private static final RowMapper<FreezeEvent> MAPPER = (rs, rowNum) -> new FreezeEvent(
            rs.getLong("id"), rs.getLong("freeze_id"), rs.getString("version"),
            FreezeEventType.valueOf(rs.getString("event_type")), rs.getString("operator"),
            rs.getString("reason"), rs.getString("event_at_utc"));

    private static final String COLUMNS = "id, freeze_id, version, event_type, operator, reason, event_at_utc";

    private final JdbcTemplate jdbc;

    public FreezeEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long freezeId, String version, FreezeEventType eventType, String operator,
                       String reason, String eventAtUtc) {
        jdbc.update("INSERT INTO release_freeze_event (freeze_id, version, event_type, operator, reason,"
                + " event_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                freezeId, version, eventType.name(), operator, reason, eventAtUtc);
    }

    public List<FreezeEvent> findByVersion(String version) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_freeze_event WHERE version = ? ORDER BY id",
                MAPPER, version);
    }

    public long countByFreeze(long freezeId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze_event WHERE freeze_id = ?", Long.class, freezeId);
        return count == null ? 0 : count;
    }
}
