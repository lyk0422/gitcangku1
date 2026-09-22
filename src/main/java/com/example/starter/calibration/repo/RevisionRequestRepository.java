package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.RevisionRequest;

/**
 * 测量修订幂等请求持久化。只记录成功的修订请求；
 * (measurement_key, request_id) 唯一，用于同参重放与改参冲突裁决。
 */
@Repository
public class RevisionRequestRepository {

    private static final RowMapper<RevisionRequest> MAPPER = (rs, rowNum) -> new RevisionRequest(
            rs.getLong("id"),
            rs.getString("measurement_key"),
            rs.getString("request_id"),
            rs.getInt("expected_revision"),
            rs.getBigDecimal("raw_reading"),
            rs.getBigDecimal("lower_limit"),
            rs.getBigDecimal("upper_limit"),
            rs.getString("reason"),
            rs.getLong("measurement_id"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public RevisionRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次成功的修订请求；同键同 requestId 已存在时由唯一约束拒绝。
     */
    public void insert(RevisionRequest request) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO revision_request "
                            + "(measurement_key, request_id, expected_revision, raw_reading, lower_limit, "
                            + "upper_limit, reason, measurement_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, request.measurementKey());
            ps.setString(2, request.requestId());
            ps.setInt(3, request.expectedRevision());
            ps.setBigDecimal(4, request.rawReading());
            ps.setBigDecimal(5, request.lowerLimit());
            ps.setBigDecimal(6, request.upperLimit());
            ps.setString(7, request.reason());
            ps.setLong(8, request.measurementId());
            ps.setObject(9, JdbcTimes.toDb(request.createdAt()));
            return ps;
        }, keyHolder);
    }

    /**
     * 按测量键与请求 ID 查询已成功的修订请求（不加锁）。
     */
    public Optional<RevisionRequest> find(String key, String requestId) {
        return jdbc.query(
                "SELECT * FROM revision_request WHERE measurement_key = ? AND request_id = ?",
                MAPPER, key, requestId).stream().findFirst();
    }
}
