package com.example.starter.race.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 处罚表数据访问。处罚只可新增或撤销，不提供更新与删除。
 */
@Repository
public class PenaltyRepository {

    private final JdbcTemplate jdbc;

    public PenaltyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按全局唯一处罚ID查询。
     */
    public Optional<PenaltyRow> find(String penaltyId) {
        return jdbc.query("SELECT penalty_id, race_id, bib, type, amount_ms, revoked FROM penalty"
                        + " WHERE penalty_id = ?",
                (rs, i) -> new PenaltyRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), (Long) rs.getObject(5, Long.class), rs.getBoolean(6)),
                penaltyId).stream().findFirst();
    }

    /**
     * 查询赛事全部未撤销处罚，用于成绩计算。
     */
    public List<PenaltyRow> findActiveByRace(String raceId) {
        return jdbc.query("SELECT penalty_id, race_id, bib, type, amount_ms, revoked FROM penalty"
                        + " WHERE race_id = ? AND revoked = FALSE",
                (rs, i) -> new PenaltyRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), (Long) rs.getObject(5, Long.class), rs.getBoolean(6)),
                raceId);
    }

    /**
     * 新增处罚；amountMs 仅 TIME_ADD 类型非空。
     */
    public void insert(String penaltyId, String raceId, String bib, String type, Long amountMs) {
        jdbc.update("INSERT INTO penalty (penalty_id, race_id, bib, type, amount_ms, revoked)"
                + " VALUES (?, ?, ?, ?, ?, FALSE)", penaltyId, raceId, bib, type, amountMs);
    }

    /**
     * 撤销处罚。
     */
    public void revoke(String penaltyId) {
        jdbc.update("UPDATE penalty SET revoked = TRUE WHERE penalty_id = ?", penaltyId);
    }
}
