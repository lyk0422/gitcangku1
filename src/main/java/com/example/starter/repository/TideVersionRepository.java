package com.example.starter.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.po.TideSegmentPo;
import com.example.starter.po.TideVersionPo;

/**
 * 潮高版本与区间仓储；版本发布在同一事务内写入版本头与全部区间。
 */
@Repository
public class TideVersionRepository {

    private static final RowMapper<TideVersionPo> VERSION_MAPPER = (rs, n) -> new TideVersionPo(
            rs.getLong("id"),
            rs.getLong("version_no"),
            rs.getTimestamp("range_start").toLocalDateTime(),
            rs.getTimestamp("range_end").toLocalDateTime(),
            rs.getString("status"),
            rs.getTimestamp("published_at").toLocalDateTime());

    private static final RowMapper<TideSegmentPo> SEGMENT_MAPPER = (rs, n) -> new TideSegmentPo(
            rs.getLong("id"),
            rs.getLong("version_id"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime(),
            rs.getBigDecimal("height"));

    private final JdbcTemplate jdbcTemplate;

    public TideVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TideVersionPo findCurrent() {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, version_no, range_start, range_end, status, published_at "
                            + "FROM tide_version WHERE status = 'CURRENT'", VERSION_MAPPER);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public TideVersionPo findById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, version_no, range_start, range_end, status, published_at "
                            + "FROM tide_version WHERE id = ?", VERSION_MAPPER, id);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<TideSegmentPo> findSegments(Long versionId) {
        return jdbcTemplate.query(
                "SELECT id, version_id, start_time, end_time, height FROM tide_segment "
                        + "WHERE version_id = ? ORDER BY start_time", SEGMENT_MAPPER, versionId);
    }

    /**
     * 在同一事务内生成新版本号、写入版本头和区间，并将旧 CURRENT 版本置为 SUPERSEDED。
     *
     * @return 含自增主键的新版本
     */
    @Transactional
    public TideVersionPo insertVersion(LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                       LocalDateTime publishedAt,
                                       List<TideSegmentPo> segments) {
        Long maxNo = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM tide_version", Long.class);
        long nextNo = (maxNo == null ? 0L : maxNo) + 1;
        jdbcTemplate.update("UPDATE tide_version SET status = 'SUPERSEDED' WHERE status = 'CURRENT'");
        jdbcTemplate.update(
                "INSERT INTO tide_version (version_no, range_start, range_end, status, published_at) "
                        + "VALUES (?, ?, ?, 'CURRENT', ?)",
                nextNo, rangeStart, rangeEnd, publishedAt);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM tide_version WHERE version_no = ?", Long.class, nextNo);
        for (TideSegmentPo segment : segments) {
            jdbcTemplate.update(
                    "INSERT INTO tide_segment (version_id, start_time, end_time, height) "
                            + "VALUES (?, ?, ?, ?)",
                    versionId, segment.startTime(), segment.endTime(), segment.height());
        }
        return new TideVersionPo(versionId, nextNo, rangeStart, rangeEnd, "CURRENT", publishedAt);
    }
}
