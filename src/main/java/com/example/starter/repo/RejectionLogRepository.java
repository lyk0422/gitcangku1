package com.example.starter.repo;

import com.example.starter.domain.TimelineEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 业务拒绝日志存取：独立于业务事务提交，供时间线展示拒绝原因。
 */
@Repository
public class RejectionLogRepository {

    private final JdbcTemplate jdbc;

    public RejectionLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一次业务拒绝。
     */
    public void insert(String operation, String tagCode, String animalCode, String stationCode,
            String reason, Instant occurredAt) {
        jdbc.update(
                "INSERT INTO rejection_log (operation, tag_code, animal_code, station_code, reason, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                operation, tagCode, animalCode, stationCode, reason, Times.toTimestamp(occurredAt));
    }

    /**
     * 按标签查询拒绝事件，按发生时刻升序。
     */
    public List<TimelineEvent> listByTag(String tagCode) {
        return jdbc.query(
                "SELECT * FROM rejection_log WHERE tag_code = ? ORDER BY occurred_at, id",
                (rs, rowNum) -> new TimelineEvent("REJECTED",
                        Times.toInstant(rs.getTimestamp("occurred_at")), rs.getString("tag_code"),
                        rs.getString("animal_code"), rs.getString("station_code"), null, null, null,
                        rs.getString("reason")),
                tagCode);
    }

    /**
     * 按动物查询拒绝事件，按发生时刻升序。
     */
    public List<TimelineEvent> listByAnimal(String animalCode) {
        return jdbc.query(
                "SELECT * FROM rejection_log WHERE animal_code = ? ORDER BY occurred_at, id",
                (rs, rowNum) -> new TimelineEvent("REJECTED",
                        Times.toInstant(rs.getTimestamp("occurred_at")), rs.getString("tag_code"),
                        rs.getString("animal_code"), rs.getString("station_code"), null, null, null,
                        rs.getString("reason")),
                animalCode);
    }
}