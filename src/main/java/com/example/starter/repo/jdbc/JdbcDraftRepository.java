package com.example.starter.repo.jdbc;

import com.example.starter.domain.Draft;
import com.example.starter.domain.DraftSegment;
import com.example.starter.repo.DraftRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 编排草稿的 JDBC 持久化实现：版本行与片段在同一事务内整份替换。
 */
@Repository
public class JdbcDraftRepository implements DraftRepository {

    private final JdbcTemplate jdbc;

    public JdbcDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Draft> find(String channelId, LocalDate businessDay) {
        List<Long> versions = jdbc.query(
                "SELECT version FROM drafts WHERE channel_id=? AND business_day=?",
                (rs, i) -> rs.getLong("version"), channelId, Date.valueOf(businessDay));
        if (versions.isEmpty()) {
            return Optional.empty();
        }
        List<DraftSegment> segments = loadSegments(channelId, businessDay);
        return Optional.of(new Draft(channelId, businessDay, versions.get(0), segments));
    }

    @Override
    public boolean replace(String channelId, LocalDate businessDay, long expectedVersion,
                           long newVersion, List<DraftSegment> segments) {
        Date day = Date.valueOf(businessDay);
        long now = System.currentTimeMillis();
        if (expectedVersion == 0) {
            try {
                jdbc.update("INSERT INTO drafts(channel_id, business_day, version, updated_at)"
                        + " VALUES (?,?,?,?)", channelId, day, newVersion, now);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                return false;
            }
        } else {
            int updated = jdbc.update(
                    "UPDATE drafts SET version=?, updated_at=?"
                            + " WHERE channel_id=? AND business_day=? AND version=?",
                    newVersion, now, channelId, day, expectedVersion);
            if (updated == 0) {
                return false;
            }
        }
        jdbc.update("DELETE FROM draft_segments WHERE channel_id=? AND business_day=?",
                channelId, day);
        jdbc.batchUpdate(
                "INSERT INTO draft_segments(channel_id, business_day, segment_id, asset_id,"
                        + " start_ms, end_ms, seq) VALUES (?,?,?,?,?,?,?)",
                segments, segments.size(), (ps, s) -> {
                    ps.setString(1, channelId);
                    ps.setDate(2, day);
                    ps.setString(3, s.segmentId());
                    ps.setString(4, s.assetId());
                    ps.setLong(5, s.start().toEpochMilli());
                    ps.setLong(6, s.end().toEpochMilli());
                    ps.setInt(7, segments.indexOf(s));
                });
        return true;
    }

    private List<DraftSegment> loadSegments(String channelId, LocalDate businessDay) {
        return jdbc.query(
                "SELECT segment_id, asset_id, start_ms, end_ms FROM draft_segments"
                        + " WHERE channel_id=? AND business_day=? ORDER BY seq",
                (rs, i) -> new DraftSegment(rs.getString("segment_id"), rs.getString("asset_id"),
                        Instant.ofEpochMilli(rs.getLong("start_ms")),
                        Instant.ofEpochMilli(rs.getLong("end_ms"))),
                channelId, Date.valueOf(businessDay));
    }
}
