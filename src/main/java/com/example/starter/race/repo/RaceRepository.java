package com.example.starter.race.repo;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 赛事表数据访问。
 */
@Repository
public class RaceRepository {

    private final JdbcTemplate jdbc;

    public RaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按主键查询赛事，不加锁。
     */
    public Optional<RaceRow> find(String raceId) {
        return jdbc.query("SELECT race_id, version, status FROM race WHERE race_id = ?",
                (rs, i) -> new RaceRow(rs.getString(1), rs.getLong(2), rs.getString(3)), raceId)
                .stream().findFirst();
    }

    /**
     * 按主键查询并锁定赛事行（FOR UPDATE），用于串行化同一赛事的写操作。
     */
    public Optional<RaceRow> findForUpdate(String raceId) {
        return jdbc.query("SELECT race_id, version, status FROM race WHERE race_id = ? FOR UPDATE",
                (rs, i) -> new RaceRow(rs.getString(1), rs.getLong(2), rs.getString(3)), raceId)
                .stream().findFirst();
    }

    /**
     * 新建赛事，初始版本为1，状态OPEN。
     */
    public void insert(String raceId) {
        jdbc.update("INSERT INTO race (race_id, version, status) VALUES (?, 1, 'OPEN')", raceId);
    }

    /**
     * 版本加一（乐观锁条件更新兜底）；返回是否更新成功。
     */
    public boolean incrementVersion(String raceId, long expectedVersion) {
        return jdbc.update("UPDATE race SET version = version + 1 WHERE race_id = ? AND version = ?",
                raceId, expectedVersion) == 1;
    }

    /**
     * 将赛事状态置为 SEALED（封榜不增加版本号）。
     */
    public void markSealed(String raceId) {
        jdbc.update("UPDATE race SET status = 'SEALED' WHERE race_id = ?", raceId);
    }
}
