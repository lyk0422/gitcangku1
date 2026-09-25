package com.example.starter.race.persistence;

import com.example.starter.race.domain.RelayTeamStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 接力领域的 JDBC 仓储；写方法均假定运行在业务事务内。
 * 棒次数组（各棒耗时/交接用时/完成时刻/犯规清单）以 JSON 文本固化在单行。
 */
@Repository
public class RelayRepository {

    private static final RelayTeamRowMapper TEAM_MAPPER = new RelayTeamRowMapper();
    private static final RelayMemberRowMapper MEMBER_MAPPER = new RelayMemberRowMapper();
    private static final RelayHandoffRowMapper HANDOFF_MAPPER = new RelayHandoffRowMapper();
    private static final RelayFoulRowMapper FOUL_MAPPER = new RelayFoulRowMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RelayRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 列出赛事下全部接力队伍，按队伍标识字典序。 */
    public List<RelayTeamRow> findTeams(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, created_at FROM relay_team WHERE race_id = ? ORDER BY team_key",
                TEAM_MAPPER, raceId);
    }

    /** 按赛事与队伍标识查询队伍。 */
    public Optional<RelayTeamRow> findTeam(String raceId, String teamKey) {
        return jdbcTemplate.query(
                        "SELECT id, race_id, team_key, created_at FROM relay_team "
                                + "WHERE race_id = ? AND team_key = ?",
                        TEAM_MAPPER, raceId, teamKey)
                .stream()
                .findFirst();
    }

    /** 新建接力队伍。 */
    public void insertTeam(String raceId, String teamKey, long now) {
        jdbcTemplate.update(
                "INSERT INTO relay_team (race_id, team_key, created_at) VALUES (?, ?, ?)",
                raceId, teamKey, now);
    }

    /** 查询某队全部棒次选手，按棒次升序。 */
    public List<RelayMemberRow> findMembers(String raceId, String teamKey) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, bib, created_at FROM relay_team_member "
                        + "WHERE race_id = ? AND team_key = ? ORDER BY leg_no",
                MEMBER_MAPPER, raceId, teamKey);
    }

    /** 查询赛事下全部队伍的棒次选手（封榜快照用）。 */
    public List<RelayMemberRow> findAllMembers(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, bib, created_at FROM relay_team_member "
                        + "WHERE race_id = ? ORDER BY team_key, leg_no",
                MEMBER_MAPPER, raceId);
    }

    /** 批量写入棒次选手。 */
    public void insertMembers(List<RelayMemberRow> members) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO relay_team_member (race_id, team_key, leg_no, bib, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                members,
                members.size(),
                (ps, m) -> {
                    ps.setString(1, m.raceId());
                    ps.setString(2, m.teamKey());
                    ps.setInt(3, m.legNo());
                    ps.setString(4, m.bib());
                    ps.setLong(5, m.createdAt());
                });
    }

    /** 查询某队全部成功交接，按棒次升序。 */
    public List<RelayHandoffRow> findHandoffs(String raceId, String teamKey) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, bib, elapsed_millis, handoff_millis, "
                        + "foul, completed_at, created_at FROM relay_handoff "
                        + "WHERE race_id = ? AND team_key = ? ORDER BY leg_no",
                HANDOFF_MAPPER, raceId, teamKey);
    }

    /** 查询赛事下全部成功交接（封榜快照用）。 */
    public List<RelayHandoffRow> findAllHandoffs(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, bib, elapsed_millis, handoff_millis, "
                        + "foul, completed_at, created_at FROM relay_handoff "
                        + "WHERE race_id = ? ORDER BY team_key, leg_no",
                HANDOFF_MAPPER, raceId);
    }

    /** 查询某队某棒次交接（已成功交接才存在）。 */
    public Optional<RelayHandoffRow> findHandoff(String raceId, String teamKey, int legNo) {
        return jdbcTemplate.query(
                        "SELECT id, race_id, team_key, leg_no, bib, elapsed_millis, handoff_millis, "
                                + "foul, completed_at, created_at FROM relay_handoff "
                                + "WHERE race_id = ? AND team_key = ? AND leg_no = ?",
                        HANDOFF_MAPPER, raceId, teamKey, legNo)
                .stream()
                .findFirst();
    }

    /** 写入一次成功交接。 */
    public void insertHandoff(RelayHandoffRow handoff) {
        jdbcTemplate.update(
                "INSERT INTO relay_handoff "
                        + "(race_id, team_key, leg_no, bib, elapsed_millis, handoff_millis, "
                        + "foul, completed_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                handoff.raceId(), handoff.teamKey(), handoff.legNo(), handoff.bib(),
                handoff.elapsedMillis(), handoff.handoffMillis(), handoff.foul(),
                handoff.completedAt(), handoff.createdAt());
    }

    /** 查询某队全部犯规，按棒次升序。 */
    public List<RelayFoulRow> findFouls(String raceId, String teamKey) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, handoff_millis, limit_millis, created_at "
                        + "FROM relay_foul WHERE race_id = ? AND team_key = ? ORDER BY leg_no",
                FOUL_MAPPER, raceId, teamKey);
    }

    /** 查询赛事下全部犯规，按队伍、棒次升序（封榜快照用）。 */
    public List<RelayFoulRow> findAllFouls(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, team_key, leg_no, handoff_millis, limit_millis, created_at "
                        + "FROM relay_foul WHERE race_id = ? ORDER BY team_key, leg_no",
                FOUL_MAPPER, raceId);
    }

    /** 写入一条犯规记录（同队同棒次唯一约束保证只记一次）。 */
    public void insertFoul(RelayFoulRow foul) {
        jdbcTemplate.update(
                "INSERT INTO relay_foul "
                        + "(race_id, team_key, leg_no, handoff_millis, limit_millis, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                foul.raceId(), foul.teamKey(), foul.legNo(), foul.handoffMillis(),
                foul.limitMillis(), foul.createdAt());
    }

    /** 查询某队完赛记录；未完赛返回 empty。 */
    public Optional<RelayFinishRow> findFinish(String raceId, String teamKey) {
        return jdbcTemplate.query(
                        "SELECT race_id, team_key, leg_count, leg_elapsed_millis, foul_legs, "
                                + "total_fouls, total_elapsed_millis, status, finished_at, created_at "
                                + "FROM relay_finish WHERE race_id = ? AND team_key = ?",
                        new RelayFinishRowMapper(), raceId, teamKey)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部完赛记录。 */
    public List<RelayFinishRow> findAllFinishes(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, leg_count, leg_elapsed_millis, foul_legs, total_fouls, "
                        + "total_elapsed_millis, status, finished_at, created_at FROM relay_finish "
                        + "WHERE race_id = ? ORDER BY team_key",
                new RelayFinishRowMapper(), raceId);
    }

    /** 写入末棒完成后固化的完赛记录。 */
    public void insertFinish(RelayFinishRow finish) {
        jdbcTemplate.update(
                "INSERT INTO relay_finish (race_id, team_key, leg_count, leg_elapsed_millis, foul_legs, "
                        + "total_fouls, total_elapsed_millis, status, finished_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                finish.raceId(), finish.teamKey(), finish.legCount(),
                writeLongs(finish.legElapsedMillis()), writeInts(finish.foulLegs()),
                finish.totalFouls(), finish.totalElapsedMillis(), finish.status().name(),
                finish.finishedAt(), finish.createdAt());
    }

    /**
     * 条件配置接力：仅当赛事 OPEN、版本等于 expectedVersion 且尚未开启接力时生效，
     * 同时把版本加一。
     *
     * @return 受影响行数；0 表示不存在、已封榜、版本不符或已配置接力
     */
    public int configureRelayIfOpenAtVersion(
            String raceId, int legCount, int handoffLimitMs, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET relay_enabled = TRUE, leg_count = ?, handoff_limit_ms = ?, "
                        + "version = version + 1 "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN' "
                        + "AND relay_enabled = FALSE",
                legCount, handoffLimitMs, raceId, expectedVersion);
    }

    /** 查询封榜快照中全部接力队伍，按展示顺序。 */
    public List<RelaySnapshotTeamRow> findSnapshotTeams(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, rank_no, status, leg_count, leg_bibs, leg_elapsed_millis, "
                        + "leg_handoff_millis, leg_completed_at, foul_legs, total_fouls, "
                        + "total_elapsed_millis, finished_at, display_order FROM relay_snapshot_team "
                        + "WHERE race_id = ? ORDER BY display_order",
                new RelaySnapshotTeamRowMapper(), raceId);
    }

    /** 原子写入封榜快照中的全部接力队伍明细。 */
    public void insertSnapshotTeams(List<RelaySnapshotTeamRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        String raceId = rows.getFirst().raceId();
        jdbcTemplate.batchUpdate(
                "INSERT INTO relay_snapshot_team (race_id, team_key, rank_no, status, leg_count, "
                        + "leg_bibs, leg_elapsed_millis, leg_handoff_millis, leg_completed_at, foul_legs, "
                        + "total_fouls, total_elapsed_millis, finished_at, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, r) -> {
                    ps.setString(1, r.raceId());
                    ps.setString(2, r.teamKey());
                    ps.setObject(3, r.rankNo());
                    ps.setString(4, r.status().name());
                    ps.setInt(5, r.legCount());
                    ps.setString(6, writeStrings(r.legBibs()));
                    ps.setString(7, writeLongs(r.legElapsedMillis()));
                    ps.setString(8, writeLongs(r.legHandoffMillis()));
                    ps.setString(9, writeLongs(r.legCompletedAt()));
                    ps.setString(10, writeInts(r.foulLegs()));
                    ps.setInt(11, r.totalFouls());
                    ps.setObject(12, r.totalElapsedMillis());
                    ps.setObject(13, r.finishedAt());
                    ps.setInt(14, r.displayOrder());
                });
    }

    private String writeLongs(List<Long> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化长整型数组失败", ex);
        }
    }

    private String writeInts(List<Integer> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化整型数组失败", ex);
        }
    }

    private String writeStrings(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("序列化字符串数组失败", ex);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalStateException("解析JSON数组失败: " + json, ex);
        }
    }

    private static final class RelayTeamRowMapper implements RowMapper<RelayTeamRow> {
        @Override
        public RelayTeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelayTeamRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    rs.getLong("created_at"));
        }
    }

    private static final class RelayMemberRowMapper implements RowMapper<RelayMemberRow> {
        @Override
        public RelayMemberRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelayMemberRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    rs.getInt("leg_no"),
                    rs.getString("bib"),
                    rs.getLong("created_at"));
        }
    }

    private static final class RelayHandoffRowMapper implements RowMapper<RelayHandoffRow> {
        @Override
        public RelayHandoffRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelayHandoffRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    rs.getInt("leg_no"),
                    rs.getString("bib"),
                    rs.getLong("elapsed_millis"),
                    rs.getLong("handoff_millis"),
                    rs.getBoolean("foul"),
                    rs.getLong("completed_at"),
                    rs.getLong("created_at"));
        }
    }

    private static final class RelayFoulRowMapper implements RowMapper<RelayFoulRow> {
        @Override
        public RelayFoulRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelayFoulRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    rs.getInt("leg_no"),
                    rs.getLong("handoff_millis"),
                    rs.getInt("limit_millis"),
                    rs.getLong("created_at"));
        }
    }

    private final class RelayFinishRowMapper implements RowMapper<RelayFinishRow> {
        @Override
        public RelayFinishRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelayFinishRow(
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    rs.getInt("leg_count"),
                    read(rs.getString("leg_elapsed_millis"), new TypeReference<List<Long>>() {
                    }),
                    read(rs.getString("foul_legs"), new TypeReference<List<Integer>>() {
                    }),
                    rs.getInt("total_fouls"),
                    rs.getLong("total_elapsed_millis"),
                    RelayTeamStatus.valueOf(rs.getString("status")),
                    rs.getLong("finished_at"),
                    rs.getLong("created_at"));
        }
    }

    private final class RelaySnapshotTeamRowMapper implements RowMapper<RelaySnapshotTeamRow> {
        @Override
        public RelaySnapshotTeamRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RelaySnapshotTeamRow(
                    rs.getString("race_id"),
                    rs.getString("team_key"),
                    (Integer) rs.getObject("rank_no"),
                    RelayTeamStatus.valueOf(rs.getString("status")),
                    rs.getInt("leg_count"),
                    read(rs.getString("leg_bibs"), new TypeReference<List<String>>() {
                    }),
                    read(rs.getString("leg_elapsed_millis"), new TypeReference<List<Long>>() {
                    }),
                    read(rs.getString("leg_handoff_millis"), new TypeReference<List<Long>>() {
                    }),
                    read(rs.getString("leg_completed_at"), new TypeReference<List<Long>>() {
                    }),
                    read(rs.getString("foul_legs"), new TypeReference<List<Integer>>() {
                    }),
                    rs.getInt("total_fouls"),
                    (Long) rs.getObject("total_elapsed_millis"),
                    (Long) rs.getObject("finished_at"),
                    rs.getInt("display_order"));
        }
    }
}
