package com.example.starter.race.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.race.dto.StandingEntry;

/**
 * 封榜快照表数据访问。快照在封榜事务内原子写入，之后只读。
 */
@Repository
public class SnapshotRepository {

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量写入封榜快照。
     */
    public void insertAll(String raceId, List<StandingEntry> entries) {
        jdbc.batchUpdate("INSERT INTO race_snapshot (race_id, bib, status, rank_no, total_time_ms)"
                        + " VALUES (?, ?, ?, ?, ?)",
                entries, entries.size(),
                (ps, e) -> {
                    ps.setString(1, raceId);
                    ps.setString(2, e.bib());
                    ps.setString(3, e.status());
                    if (e.rank() == null) {
                        ps.setObject(4, null);
                    } else {
                        ps.setInt(4, e.rank());
                    }
                    if (e.totalTimeMs() == null) {
                        ps.setObject(5, null);
                    } else {
                        ps.setLong(5, e.totalTimeMs());
                    }
                });
    }

    /**
     * 查询赛事封榜快照，按写入顺序（名次与参赛号序）返回。
     */
    public List<StandingEntry> findByRace(String raceId) {
        return jdbc.query("SELECT bib, status, rank_no, total_time_ms FROM race_snapshot"
                        + " WHERE race_id = ? ORDER BY (rank_no IS NULL), rank_no,"
                        + " CASE status WHEN 'DISQUALIFIED' THEN 1 ELSE 0 END, bib",
                (rs, i) -> new StandingEntry(rs.getString(1), rs.getString(2),
                        (Long) rs.getObject(4, Long.class), (Integer) rs.getObject(3, Integer.class)),
                raceId);
    }
}
