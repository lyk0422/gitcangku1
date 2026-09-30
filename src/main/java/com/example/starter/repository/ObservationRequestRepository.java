package com.example.starter.repository;

import com.example.starter.domain.RequestStatus;
import com.example.starter.model.ObservationRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 观测申请仓库：申请落库、仪器行锁、重叠查询与状态推进。
 */
@Repository
public class ObservationRequestRepository {

    private static final RowMapper<ObservationRequest> MAPPER = (rs, n) -> {
        ObservationRequest r = new ObservationRequest();
        r.setId(rs.getLong("id"));
        r.setRequestNo(rs.getString("request_no"));
        r.setProject(rs.getString("project"));
        r.setInstrumentId(rs.getString("instrument_id"));
        r.setResearchGroup(rs.getString("research_group"));
        r.setWindowStart(rs.getTimestamp("window_start").toInstant());
        r.setWindowEnd(rs.getTimestamp("window_end").toInstant());
        r.setDurationMinutes(rs.getInt("duration_minutes"));
        r.setStatus(RequestStatus.valueOf(rs.getString("status")));
        r.setRequestedAt(rs.getTimestamp("requested_at").toInstant());
        Timestamp allocatedAt = rs.getTimestamp("allocated_at");
        r.setAllocatedAt(allocatedAt == null ? null : allocatedAt.toInstant());
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        r.setCancelledAt(cancelledAt == null ? null : cancelledAt.toInstant());
        r.setPromotedFromRequestNo(rs.getString("promoted_from_request_no"));
        return r;
    };

    private final JdbcTemplate jdbcTemplate;

    public ObservationRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在事务内锁定仪器对应的串行化锁行；锁行不存在时幂等创建。
     * 同一仪器的批准、撤销与接替按提交顺序在此排队裁决。
     */
    public void lockInstrument(String instrumentId) {
        insertLockRow(instrumentId);
        jdbcTemplate.queryForObject(
                "SELECT instrument_id FROM instrument_lock WHERE instrument_id = ? FOR UPDATE",
                String.class, instrumentId);
    }

    private void insertLockRow(String instrumentId) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO instrument_lock (instrument_id, created_at) VALUES (?, ?)",
                    instrumentId, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ignored) {
            // 锁行已存在，直接进入加锁查询。
        }
    }

    public void insert(ObservationRequest r) {
        jdbcTemplate.update(
                """
                INSERT INTO observation_request
                    (request_no, project, instrument_id, research_group, window_start, window_end,
                     duration_minutes, status, requested_at, allocated_at, cancelled_at, promoted_from_request_no)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.getRequestNo(), r.getProject(), r.getInstrumentId(), r.getResearchGroup(),
                Timestamp.from(r.getWindowStart()), Timestamp.from(r.getWindowEnd()),
                r.getDurationMinutes(), r.getStatus().name(), Timestamp.from(r.getRequestedAt()),
                r.getAllocatedAt() == null ? null : Timestamp.from(r.getAllocatedAt()),
                r.getCancelledAt() == null ? null : Timestamp.from(r.getCancelledAt()),
                r.getPromotedFromRequestNo());
    }

    public ObservationRequest findByRequestNo(String requestNo) {
        List<ObservationRequest> list = jdbcTemplate.query(
                "SELECT * FROM observation_request WHERE request_no = ?", MAPPER, requestNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<ObservationRequest> findByInstrument(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT * FROM observation_request WHERE instrument_id = ? ORDER BY window_start, id",
                MAPPER, instrumentId);
    }

    /** 该仪器已分配窗口时间轴，按开始时刻升序。 */
    public List<ObservationRequest> findAllocatedByInstrument(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT * FROM observation_request WHERE instrument_id = ? AND status = 'ALLOCATED' "
                        + "ORDER BY window_start, id",
                MAPPER, instrumentId);
    }

    /** 候补队列：按申请时间、申请编号升序。 */
    public List<ObservationRequest> findWaitingQueue(String instrumentId) {
        return jdbcTemplate.query(
                "SELECT * FROM observation_request WHERE instrument_id = ? AND status = 'PENDING' "
                        + "ORDER BY requested_at, request_no",
                MAPPER, instrumentId);
    }

    /**
     * 生成全局严格单调的申请序号；调用方须持有全局申请锁，数据不删除故计数即序号。
     */
    public long nextSequence() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_request", Long.class);
        return (count == null ? 0 : count) + 1;
    }

    /**
     * 乐观锁定整段窗口：仅当申请仍为 PENDING 时生效。
     *
     * @return 受影响行数；0 表示状态已被并发事务改变
     */
    public int markAllocated(String requestNo, Instant allocatedAt, String promotedFromRequestNo) {
        return jdbcTemplate.update(
                "UPDATE observation_request SET status = 'ALLOCATED', allocated_at = ?, "
                        + "promoted_from_request_no = ? WHERE request_no = ? AND status = 'PENDING'",
                Timestamp.from(allocatedAt), promotedFromRequestNo, requestNo);
    }

    /**
     * 乐观撤销：仅当申请尚未撤销时生效，重复撤销不推进候补。
     *
     * @return 受影响行数；0 表示申请已撤销
     */
    public int markCancelled(String requestNo, Instant cancelledAt) {
        return jdbcTemplate.update(
                "UPDATE observation_request SET status = 'CANCELLED', cancelled_at = ? "
                        + "WHERE request_no = ? AND status <> 'CANCELLED'",
                Timestamp.from(cancelledAt), requestNo);
    }
}
