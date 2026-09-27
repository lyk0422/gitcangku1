package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.OutlierVerdict;
import com.example.starter.calibration.model.ReviewDecision;

/**
 * 离群裁决持久化。裁决记录只增不改，同一离群记录最多一条（唯一约束兜底）。
 */
@Repository
public class VerdictRepository {

    private static final RowMapper<OutlierVerdict> MAPPER = (rs, rowNum) -> new OutlierVerdict(
            rs.getLong("id"),
            rs.getLong("outlier_id"),
            ReviewDecision.valueOf(rs.getString("decision")),
            JdbcTimes.fromDb(rs.getObject("formed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public VerdictRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入裁决记录并返回生成的 ID。
     */
    public long insert(long outlierId, ReviewDecision decision, Instant formedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO outlier_verdict (outlier_id, decision, formed_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, outlierId);
            ps.setString(2, decision.name());
            ps.setObject(3, JdbcTimes.toDb(formedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按离群记录查询裁决（不存在时为空）。
     */
    public Optional<OutlierVerdict> findByOutlierId(long outlierId) {
        return jdbc.query("SELECT * FROM outlier_verdict WHERE outlier_id = ?", MAPPER, outlierId)
                .stream().findFirst();
    }

    /**
     * 查询多个离群记录的裁决（按 ID 升序），用于批次历史查询。
     */
    public List<OutlierVerdict> findByOutlierIds(List<Long> outlierIds) {
        if (outlierIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", outlierIds.stream().map(id -> "?").toList());
        return jdbc.query("SELECT * FROM outlier_verdict WHERE outlier_id IN (" + placeholders + ") ORDER BY id",
                MAPPER, outlierIds.toArray());
    }
}
