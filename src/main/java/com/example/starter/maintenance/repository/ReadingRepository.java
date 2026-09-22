package com.example.starter.maintenance.repository;

import com.example.starter.maintenance.model.Reading;
import com.example.starter.maintenance.model.ReadingRevision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 工时读数及修订历史数据访问。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Reading mapReading(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Reading(rs.getString("reading_id"),
                Instant.ofEpochMilli(rs.getLong("sampled_at_epoch_ms")),
                rs.getLong("accumulated_minutes"),
                rs.getInt("current_revision"));
    }

    public void insert(String equipmentId, String readingId, long sampledAtEpochMs,
                       long accumulatedMinutes, long nowEpochMs) {
        jdbc.update("INSERT INTO equipment_reading"
                        + " (equipment_id, reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision, created_at_epoch_ms)"
                        + " VALUES (?, ?, ?, ?, 1, ?)",
                equipmentId, readingId, sampledAtEpochMs, accumulatedMinutes, nowEpochMs);
    }

    public Optional<Reading> findById(String equipmentId, String readingId) {
        return jdbc.query("SELECT reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision"
                        + " FROM equipment_reading WHERE equipment_id = ? AND reading_id = ?",
                ReadingRepository::mapReading, equipmentId, readingId).stream().findFirst();
    }

    public boolean existsBySampledAt(String equipmentId, long sampledAtEpochMs) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_reading"
                + " WHERE equipment_id = ? AND sampled_at_epoch_ms = ?", Integer.class, equipmentId, sampledAtEpochMs);
        return count != null && count > 0;
    }

    /**
     * 采样时刻严格早于给定时刻的最近一条读数（前邻）。
     */
    public Optional<Reading> findPrevious(String equipmentId, long sampledAtEpochMs) {
        return jdbc.query("SELECT reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision"
                        + " FROM equipment_reading WHERE equipment_id = ? AND sampled_at_epoch_ms < ?"
                        + " ORDER BY sampled_at_epoch_ms DESC LIMIT 1",
                ReadingRepository::mapReading, equipmentId, sampledAtEpochMs).stream().findFirst();
    }

    /**
     * 采样时刻严格晚于给定时刻的最近一条读数（后邻）。
     */
    public Optional<Reading> findNext(String equipmentId, long sampledAtEpochMs) {
        return jdbc.query("SELECT reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision"
                        + " FROM equipment_reading WHERE equipment_id = ? AND sampled_at_epoch_ms > ?"
                        + " ORDER BY sampled_at_epoch_ms ASC LIMIT 1",
                ReadingRepository::mapReading, equipmentId, sampledAtEpochMs).stream().findFirst();
    }

    public List<Reading> findAllByEquipment(String equipmentId) {
        return jdbc.query("SELECT reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision"
                        + " FROM equipment_reading WHERE equipment_id = ? ORDER BY sampled_at_epoch_ms ASC",
                ReadingRepository::mapReading, equipmentId);
    }

    public Optional<Reading> findLatest(String equipmentId) {
        return jdbc.query("SELECT reading_id, sampled_at_epoch_ms, accumulated_minutes, current_revision"
                        + " FROM equipment_reading WHERE equipment_id = ?"
                        + " ORDER BY sampled_at_epoch_ms DESC LIMIT 1",
                ReadingRepository::mapReading, equipmentId).stream().findFirst();
    }

    /**
     * 修订：仅更新累计分钟并递增修订号。
     */
    public void updateAccumulatedMinutes(String equipmentId, String readingId, long accumulatedMinutes) {
        jdbc.update("UPDATE equipment_reading SET accumulated_minutes = ?, current_revision = current_revision + 1"
                + " WHERE equipment_id = ? AND reading_id = ?", accumulatedMinutes, equipmentId, readingId);
    }

    public void insertRevision(String equipmentId, String readingId, int revisionNo,
                               long accumulatedMinutes, long revisedAtEpochMs) {
        jdbc.update("INSERT INTO reading_revision"
                        + " (equipment_id, reading_id, revision_no, accumulated_minutes, revised_at_epoch_ms)"
                        + " VALUES (?, ?, ?, ?, ?)",
                equipmentId, readingId, revisionNo, accumulatedMinutes, revisedAtEpochMs);
    }

    public List<ReadingRevision> findRevisions(String equipmentId, String readingId) {
        return jdbc.query("SELECT revision_no, accumulated_minutes, revised_at_epoch_ms FROM reading_revision"
                        + " WHERE equipment_id = ? AND reading_id = ? ORDER BY revision_no ASC",
                (rs, i) -> new ReadingRevision(rs.getInt(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(3))),
                equipmentId, readingId);
    }
}
