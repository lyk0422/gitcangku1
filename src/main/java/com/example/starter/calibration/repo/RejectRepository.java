package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.RejectRecord;

/**
 * 驳回历史持久化。仅未放行测量可驳回；历史只增不改。
 */
@Repository
public class RejectRepository {

    private static final RowMapper<RejectRecord> MAPPER = (rs, rowNum) -> new RejectRecord(
            rs.getLong("id"),
            rs.getLong("measurement_id"),
            rs.getString("rejected_by"),
            rs.getString("reason"),
            JdbcTimes.fromDb(rs.getObject("rejected_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public RejectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条驳回历史。
     */
    public void insert(long measurementId, String rejectedBy, String reason, Instant rejectedAt) {
        jdbc.update("INSERT INTO reject_record (measurement_id, rejected_by, reason, rejected_at) "
                        + "VALUES (?, ?, ?, ?)",
                measurementId, rejectedBy, reason, JdbcTimes.toDb(rejectedAt));
    }

    /**
     * 查询某测量的全部驳回历史（按 ID 升序）。
     */
    public List<RejectRecord> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM reject_record WHERE measurement_id = ? ORDER BY id",
                MAPPER, measurementId);
    }
}
