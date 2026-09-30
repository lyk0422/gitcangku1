package com.example.starter.repository;

import com.example.starter.web.dto.SlotView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 仪器已分配时间轴仓储。时间列均按 UTC 解释，区间左闭右开。
 */
@Repository
public class InstrumentSlotRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentSlotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入已分配区间。
     */
    public void insert(String instrumentId, long requestId, Instant startAt, Instant endAt) {
        jdbcTemplate.update(
                "INSERT INTO instrument_slot (instrument_id, request_id, start_at, end_at)"
                        + " VALUES (?, ?, ?, ?)",
                instrumentId, requestId,
                Timestamp.valueOf(LocalDateTime.ofInstant(startAt, ZoneOffset.UTC)),
                Timestamp.valueOf(LocalDateTime.ofInstant(endAt, ZoneOffset.UTC)));
    }

    /**
     * 按申请删除区间（撤销时释放空档）。
     */
    public void deleteByRequestId(long requestId) {
        jdbcTemplate.update("DELETE FROM instrument_slot WHERE request_id = ?", requestId);
    }

    /**
     * 判断同仪器是否存在与 [startAt, endAt) 重叠的已分配区间。
     * 左闭右开区间重叠条件：已存开始 &lt; 新结束 且 新开始 &lt; 已存结束。
     * 必须在仪器行锁保护的事务内调用。
     */
    public boolean existsOverlapping(String instrumentId, Instant startAt, Instant endAt) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*) FROM instrument_slot
                        WHERE instrument_id = ? AND start_at < ? AND end_at > ?""",
                Integer.class, instrumentId,
                Timestamp.valueOf(LocalDateTime.ofInstant(endAt, ZoneOffset.UTC)),
                Timestamp.valueOf(LocalDateTime.ofInstant(startAt, ZoneOffset.UTC)));
        return count != null && count > 0;
    }

    /**
     * 某仪器时间轴，按开始时刻升序。关联申请以区分普通批准与候补接替。
     */
    public List<SlotView> findTimelineByInstrument(String instrumentId) {
        return jdbcTemplate.query("""
                        SELECT s.start_at, s.end_at,
                               r.request_no, r.project_id,
                               r.status,
                               p.request_no AS promoted_from_no
                        FROM instrument_slot s
                        JOIN observation_request r ON r.id = s.request_id
                        LEFT JOIN observation_request p ON p.id = r.promoted_from_request_id
                        WHERE s.instrument_id = ?
                        ORDER BY s.start_at ASC, r.request_no ASC""",
                new SlotRowMapper(), instrumentId);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /**
     * 时间轴行映射器。
     */
    private static final class SlotRowMapper implements RowMapper<SlotView> {

        @Override
        public SlotView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SlotView(
                    rs.getString("request_no"),
                    rs.getString("project_id"),
                    toInstant(rs.getTimestamp("start_at")),
                    toInstant(rs.getTimestamp("end_at")),
                    "PROMOTED".equals(rs.getString("status")),
                    rs.getString("promoted_from_no"));
        }
    }
}
