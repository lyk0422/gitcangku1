package com.example.starter.race.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 参赛选手表数据访问。
 */
@Repository
public class ParticipantRepository {

    private final JdbcTemplate jdbc;

    public ParticipantRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按赛事与参赛号查询选手。
     */
    public Optional<ParticipantRow> find(String raceId, String bib) {
        return jdbc.query("SELECT race_id, bib, raw_time_ms FROM participant WHERE race_id = ? AND bib = ?",
                (rs, i) -> new ParticipantRow(rs.getString(1), rs.getString(2),
                        (Long) rs.getObject(3, Long.class)), raceId, bib)
                .stream().findFirst();
    }

    /**
     * 查询赛事全部选手，按参赛号字典序。
     */
    public List<ParticipantRow> findByRace(String raceId) {
        return jdbc.query("SELECT race_id, bib, raw_time_ms FROM participant WHERE race_id = ? ORDER BY bib",
                (rs, i) -> new ParticipantRow(rs.getString(1), rs.getString(2),
                        (Long) rs.getObject(3, Long.class)), raceId);
    }

    /**
     * 登记选手；rawTimeMs 为 null 表示计时缺失。
     */
    public void insert(String raceId, String bib, Long rawTimeMs) {
        jdbc.update("INSERT INTO participant (race_id, bib, raw_time_ms) VALUES (?, ?, ?)",
                raceId, bib, rawTimeMs);
    }

    /**
     * 修订原始完赛耗时。
     */
    public void updateRawTime(String raceId, String bib, long rawTimeMs) {
        jdbc.update("UPDATE participant SET raw_time_ms = ? WHERE race_id = ? AND bib = ?",
                rawTimeMs, raceId, bib);
    }
}
