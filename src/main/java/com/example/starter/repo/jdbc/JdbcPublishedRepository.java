package com.example.starter.repo.jdbc;

import com.example.starter.domain.DraftSegment;
import com.example.starter.domain.PublishedSchedule;
import com.example.starter.repo.PublishedRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 已发布编排的 JDBC 持久化实现：发布版本行与只读快照在同一事务内写入。
 */
@Repository
public class JdbcPublishedRepository implements PublishedRepository {

    private final JdbcTemplate jdbc;

    public JdbcPublishedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PublishedSchedule> find(String channelId, LocalDate businessDay) {
        List<long[]> rows = jdbc.query(
                "SELECT version, draft_version FROM published"
                        + " WHERE channel_id=? AND business_day=?",
                (rs, i) -> new long[]{rs.getLong("version"), rs.getLong("draft_version")},
                channelId, Date.valueOf(businessDay));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        long version = rows.get(0)[0];
        long draftVersion = rows.get(0)[1];
        List<DraftSegment> segments = jdbc.query(
                "SELECT segment_id, asset_id, start_ms, end_ms FROM published_segments"
                        + " WHERE channel_id=? AND business_day=? ORDER BY seq",
                (rs, i) -> new DraftSegment(rs.getString("segment_id"), rs.getString("asset_id"),
                        Instant.ofEpochMilli(rs.getLong("start_ms")),
                        Instant.ofEpochMilli(rs.getLong("end_ms"))),
                channelId, Date.valueOf(businessDay));
        return Optional.of(new PublishedSchedule(channelId, businessDay, version,
                draftVersion, segments));
    }

    @Override
    public boolean publish(String channelId, LocalDate businessDay, long expectedVersion,
                           long newVersion, long draftVersion, List<DraftSegment> segments) {
        Date day = Date.valueOf(businessDay);
        long now = System.currentTimeMillis();
        if (expectedVersion == 0) {
            try {
                jdbc.update("INSERT INTO published(channel_id, business_day, version,"
                        + " draft_version, published_at) VALUES (?,?,?,?,?)",
                        channelId, day, newVersion, draftVersion, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return false;
            }
        } else {
            int updated = jdbc.update(
                    "UPDATE published SET version=?, draft_version=?, published_at=?"
                            + " WHERE channel_id=? AND business_day=? AND version=?",
                    newVersion, draftVersion, now, channelId, day, expectedVersion);
            if (updated == 0) {
                return false;
            }
        }
        jdbc.update("DELETE FROM published_segments WHERE channel_id=? AND business_day=?",
                channelId, day);
        jdbc.batchUpdate(
                "INSERT INTO published_segments(channel_id, business_day, published_version,"
                        + " segment_id, asset_id, start_ms, end_ms, seq)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                segments, segments.size(), (ps, s) -> {
                    ps.setString(1, channelId);
                    ps.setDate(2, day);
                    ps.setLong(3, newVersion);
                    ps.setString(4, s.segmentId());
                    ps.setString(5, s.assetId());
                    ps.setLong(6, s.start().toEpochMilli());
                    ps.setLong(7, s.end().toEpochMilli());
                    ps.setInt(8, segments.indexOf(s));
                });
        return true;
    }
}
