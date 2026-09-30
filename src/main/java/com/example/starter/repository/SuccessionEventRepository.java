package com.example.starter.repository;

import com.example.starter.model.SuccessionEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 候补接替流水仓库。
 */
@Repository
public class SuccessionEventRepository {

    private static final RowMapper<SuccessionEvent> MAPPER = (rs, n) -> {
        SuccessionEvent e = new SuccessionEvent();
        e.setId(rs.getLong("id"));
        e.setInstrumentId(rs.getString("instrument_id"));
        e.setVacatedRequestNo(rs.getString("vacated_request_no"));
        e.setPromotedRequestNo(rs.getString("promoted_request_no"));
        e.setGapStart(rs.getTimestamp("gap_start").toInstant());
        e.setGapEnd(rs.getTimestamp("gap_end").toInstant());
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return e;
    };

    private final JdbcTemplate jdbcTemplate;

    public SuccessionEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(SuccessionEvent e) {
        jdbcTemplate.update(
                """
                INSERT INTO succession_event
                    (instrument_id, vacated_request_no, promoted_request_no, gap_start, gap_end, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                e.getInstrumentId(), e.getVacatedRequestNo(), e.getPromotedRequestNo(),
                Timestamp.from(e.getGapStart()), Timestamp.from(e.getGapEnd()),
                Timestamp.from(e.getCreatedAt()));
    }

    public List<SuccessionEvent> findByInstrument(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT * FROM succession_event WHERE instrument_id = ? ORDER BY id",
                MAPPER, instrumentId);
    }
}
