package com.example.starter.race.persistence;

import com.example.starter.race.domain.EntryStatus;
import com.example.starter.race.domain.PenaltyType;
import com.example.starter.race.domain.RaceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 赛事封榜领域的 JDBC 仓储；所有写方法均假定运行在业务事务内，
 * 版本推进与封榜全部使用条件 UPDATE 保证并发下无丢失更新。
 */
@Repository
public class RaceRepository {

    private static final RaceRowMapper RACE_ROW_MAPPER = new RaceRowMapper();
    private static final RunnerRowMapper RUNNER_ROW_MAPPER = new RunnerRowMapper();
    private static final PenaltyRowMapper PENALTY_ROW_MAPPER = new PenaltyRowMapper();
    private static final SnapshotEntryRowMapper SNAPSHOT_ENTRY_ROW_MAPPER =
            new SnapshotEntryRowMapper();
    private static final IdempotencyRowMapper IDEMPOTENCY_ROW_MAPPER = new IdempotencyRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public RaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按ID查询赛事。 */
    public Optional<RaceRow> findRace(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, version, status, created_at FROM race WHERE race_id = ?",
                        RACE_ROW_MAPPER, raceId)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部选手，按参赛号字典序排列。 */
    public List<RunnerRow> findRunners(String raceId) {
        return jdbcTemplate.query(
                "SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                        + "FROM runner WHERE race_id = ? ORDER BY bib",
                RUNNER_ROW_MAPPER, raceId);
    }

    /** 按赛事与参赛号查询选手。 */
    public Optional<RunnerRow> findRunner(String raceId, String bib) {
        return jdbcTemplate
                .query("SELECT id, race_id, bib, finish_time_ms, created_at, updated_at "
                                + "FROM runner WHERE race_id = ? AND bib = ?",
                        RUNNER_ROW_MAPPER, raceId, bib)
                .stream()
                .findFirst();
    }

    /** 查询赛事下全部处罚（含已撤销），按新增时间与处罚ID排列。 */
    public List<PenaltyRow> findPenalties(String raceId) {
        return jdbcTemplate.query(
                "SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                        + "FROM penalty WHERE race_id = ? ORDER BY created_at, penalty_id",
                PENALTY_ROW_MAPPER, raceId);
    }

    /** 按全局处罚ID查询处罚。 */
    public Optional<PenaltyRow> findPenalty(String penaltyId) {
        return jdbcTemplate
                .query("SELECT penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at "
                                + "FROM penalty WHERE penalty_id = ?",
                        PENALTY_ROW_MAPPER, penaltyId)
                .stream()
                .findFirst();
    }

    /** 查询封榜快照（含全部条目）；未封榜返回 empty。 */
    public Optional<SnapshotRow> findSnapshot(String raceId) {
        List<SnapshotRow> headers = jdbcTemplate.query(
                "SELECT race_id, version, sealed_at FROM result_snapshot WHERE race_id = ?",
                (rs, rowNum) -> new SnapshotRow(
                        rs.getString("race_id"),
                        rs.getInt("version"),
                        rs.getLong("sealed_at"),
                        List.of()),
                raceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow header = headers.getFirst();
        List<SnapshotEntryRow> entries = jdbcTemplate.query(
                "SELECT race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, display_order "
                        + "FROM result_snapshot_entry WHERE race_id = ? ORDER BY display_order",
                SNAPSHOT_ENTRY_ROW_MAPPER, raceId);
        return Optional.of(new SnapshotRow(header.raceId(), header.version(), header.sealedAt(),
                entries));
    }

    /** 新建赛事，初始版本1、状态OPEN。 */
    public void insertRace(String raceId, long now) {
        jdbcTemplate.update(
                "INSERT INTO race (race_id, version, status, created_at) VALUES (?, 1, 'OPEN', ?)",
                raceId, now);
    }

    /** 登记选手；finishTimeMs 为 null 表示计时缺失。 */
    public void insertRunner(String raceId, String bib, Long finishTimeMs, long now) {
        jdbcTemplate.update(
                "INSERT INTO runner (race_id, bib, finish_time_ms, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                raceId, bib, finishTimeMs, now, now);
    }

    /** 修订选手原始完赛耗时；返回受影响行数（0 表示选手不存在）。 */
    public int updateRunnerTiming(String raceId, String bib, Long finishTimeMs, long now) {
        return jdbcTemplate.update(
                "UPDATE runner SET finish_time_ms = ?, updated_at = ? WHERE race_id = ? AND bib = ?",
                finishTimeMs, now, raceId, bib);
    }

    /** 新增处罚（不可覆盖历史）。 */
    public void insertPenalty(
            String penaltyId,
            String raceId,
            String bib,
            PenaltyType type,
            Long amountMs,
            long now) {
        jdbcTemplate.update(
                "INSERT INTO penalty "
                        + "(penalty_id, race_id, bib, type, amount_ms, revoked, created_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, FALSE, ?, NULL)",
                penaltyId, raceId, bib, type.name(), amountMs, now);
    }

    /** 撤销处罚，仅对当前未撤销的处罚生效；返回受影响行数。 */
    public int markPenaltyRevoked(String penaltyId, long now) {
        return jdbcTemplate.update(
                "UPDATE penalty SET revoked = TRUE, revoked_at = ? WHERE penalty_id = ? AND revoked = FALSE",
                now, penaltyId);
    }

    /**
     * 条件推进版本：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时加一。
     *
     * @return 受影响行数；0 表示赛事不存在、已封榜或版本已被其他写操作推进
     */
    public int bumpVersionIfOpen(String raceId, int expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = version + 1 "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                raceId, expectedVersion);
    }

    /**
     * 条件封榜：仅当赛事仍为 OPEN 且版本等于 expectedVersion 时转为 SEALED 并推进版本。
     *
     * @return 受影响行数；0 表示不存在、已封榜或版本不匹配
     */
    public int sealIfOpenAtVersion(String raceId, int expectedVersion, int newVersion) {
        return jdbcTemplate.update(
                "UPDATE race SET version = ?, status = 'SEALED' "
                        + "WHERE race_id = ? AND version = ? AND status = 'OPEN'",
                newVersion, raceId, expectedVersion);
    }

    /** 原子写入封榜快照头表与全部条目。 */
    public void insertSnapshot(SnapshotRow snapshot) {
        jdbcTemplate.update(
                "INSERT INTO result_snapshot (race_id, version, sealed_at) VALUES (?, ?, ?)",
                snapshot.raceId(), snapshot.version(), snapshot.sealedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO result_snapshot_entry "
                        + "(race_id, bib, rank_no, status, finish_time_ms, penalty_ms, total_time_ms, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.entries(),
                snapshot.entries().size(),
                (ps, entry) -> {
                    ps.setString(1, entry.raceId());
                    ps.setString(2, entry.bib());
                    ps.setObject(3, entry.rank());
                    ps.setString(4, entry.status().name());
                    ps.setObject(5, entry.finishTimeMs());
                    ps.setLong(6, entry.penaltyMs());
                    ps.setObject(7, entry.totalTimeMs());
                    ps.setInt(8, entry.displayOrder());
                });
    }

    /** 按请求ID查询幂等记录。 */
    public Optional<IdempotencyRow> findIdempotency(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ?",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /**
     * 插入幂等占位行（response_status=0 表示进行中），同事务失败回滚即不占键。
     * 并发同键时由唯一约束拒绝，调用方再通过 {@link #findIdempotencyForUpdate} 等待先行者结束。
     */
    public void insertIdempotencyPlaceholder(
            String requestId, String operation, String requestDigest, long now) {
        jdbcTemplate.update(
                "INSERT INTO idempotency_record "
                        + "(request_id, operation, request_digest, response_status, response_body, created_at) "
                        + "VALUES (?, ?, ?, 0, '', ?)",
                requestId, operation, requestDigest, now);
    }

    /**
     * 行锁方式查询幂等记录：若先行者事务未结束则阻塞至其提交或回滚；
     * 回滚后占位行随事务消失，返回 empty，调用方可重新占位执行。
     */
    public Optional<IdempotencyRow> findIdempotencyForUpdate(String requestId) {
        return jdbcTemplate
                .query("SELECT request_id, operation, request_digest, response_status, response_body, created_at "
                                + "FROM idempotency_record WHERE request_id = ? FOR UPDATE",
                        IDEMPOTENCY_ROW_MAPPER, requestId)
                .stream()
                .findFirst();
    }

    /** 业务成功后把占位行补写为最终响应，与业务变更同事务原子提交。 */
    public void completeIdempotency(String requestId, int responseStatus, String responseBody) {
        jdbcTemplate.update(
                "UPDATE idempotency_record SET response_status = ?, response_body = ? "
                        + "WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    /** 测试辅助：清空全部业务数据，按外键依赖顺序删除。 */
    public void deleteAllForTesting() {
        jdbcTemplate.update("DELETE FROM relay_snapshot_leg");
        jdbcTemplate.update("DELETE FROM relay_snapshot_team");
        jdbcTemplate.update("DELETE FROM relay_snapshot");
        jdbcTemplate.update("DELETE FROM relay_finish");
        jdbcTemplate.update("DELETE FROM relay_foul");
        jdbcTemplate.update("DELETE FROM relay_handoff");
        jdbcTemplate.update("DELETE FROM relay_team_leg");
        jdbcTemplate.update("DELETE FROM relay_config");
        jdbcTemplate.update("DELETE FROM result_snapshot_entry");
        jdbcTemplate.update("DELETE FROM result_snapshot");
        jdbcTemplate.update("DELETE FROM idempotency_record");
        jdbcTemplate.update("DELETE FROM penalty");
        jdbcTemplate.update("DELETE FROM runner");
        jdbcTemplate.update("DELETE FROM race");
    }

    // ------------------------------------------------------------------
    // 接力模式数据访问
    // ------------------------------------------------------------------

    /** 查询接力配置；非接力赛事返回 empty。 */
    public Optional<RelayConfigRow> findRelayConfig(String raceId) {
        return jdbcTemplate
                .query("SELECT race_id, leg_count, exchange_limit_ms, created_at "
                                + "FROM relay_config WHERE race_id = ?",
                        (rs, rowNum) -> new RelayConfigRow(
                                rs.getString("race_id"),
                                rs.getInt("leg_count"),
                                rs.getLong("exchange_limit_ms"),
                                rs.getLong("created_at")),
                        raceId)
                .stream()
                .findFirst();
    }

    /** 写入接力配置（每赛事仅一份，重复由主键拒绝）。 */
    public void insertRelayConfig(String raceId, int legCount, long exchangeLimitMs, long now) {
        jdbcTemplate.update(
                "INSERT INTO relay_config (race_id, leg_count, exchange_limit_ms, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                raceId, legCount, exchangeLimitMs, now);
    }

    /** 批量写入队伍棒次选手登记；runners 下标 i 对应棒次 i+1。 */
    public void insertRelayTeamLegs(String raceId, String teamKey, List<String> runners) {
        List<RelayTeamLegRow> rows = new ArrayList<>(runners.size());
        for (int i = 0; i < runners.size(); i++) {
            rows.add(new RelayTeamLegRow(raceId, teamKey, i + 1, runners.get(i)));
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO relay_team_leg (race_id, team_key, leg_no, runner) VALUES (?, ?, ?, ?)",
                rows,
                rows.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.teamKey());
                    ps.setInt(3, row.legNo());
                    ps.setString(4, row.runner());
                });
    }

    /** 查询赛事全部队伍棒次登记，按队伍与棒次排列。 */
    public List<RelayTeamLegRow> findRelayTeamLegs(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, leg_no, runner FROM relay_team_leg "
                        + "WHERE race_id = ? ORDER BY team_key, leg_no",
                (rs, rowNum) -> new RelayTeamLegRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        rs.getInt("leg_no"),
                        rs.getString("runner")),
                raceId);
    }

    /** 查询某队某棒次登记选手。 */
    public Optional<RelayTeamLegRow> findRelayTeamLeg(String raceId, String teamKey, int legNo) {
        return jdbcTemplate
                .query("SELECT race_id, team_key, leg_no, runner FROM relay_team_leg "
                                + "WHERE race_id = ? AND team_key = ? AND leg_no = ?",
                        (rs, rowNum) -> new RelayTeamLegRow(
                                rs.getString("race_id"),
                                rs.getString("team_key"),
                                rs.getInt("leg_no"),
                                rs.getString("runner")),
                        raceId, teamKey, legNo)
                .stream()
                .findFirst();
    }

    /** 查询某队全部交接记录，按棒次排列。 */
    public List<RelayHandoffRow> findRelayHandoffs(String raceId, String teamKey) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, leg_no, receiver, elapsed_ms, zone_ms, foul, server_completed_at "
                        + "FROM relay_handoff WHERE race_id = ? AND team_key = ? ORDER BY leg_no",
                (rs, rowNum) -> new RelayHandoffRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        rs.getInt("leg_no"),
                        rs.getString("receiver"),
                        rs.getLong("elapsed_ms"),
                        rs.getLong("zone_ms"),
                        rs.getBoolean("foul"),
                        rs.getLong("server_completed_at")),
                raceId, teamKey);
    }

    /** 查询某队某棒次交接记录。 */
    public Optional<RelayHandoffRow> findRelayHandoff(String raceId, String teamKey, int legNo) {
        return jdbcTemplate
                .query("SELECT race_id, team_key, leg_no, receiver, elapsed_ms, zone_ms, foul, server_completed_at "
                                + "FROM relay_handoff WHERE race_id = ? AND team_key = ? AND leg_no = ?",
                        (rs, rowNum) -> new RelayHandoffRow(
                                rs.getString("race_id"),
                                rs.getString("team_key"),
                                rs.getInt("leg_no"),
                                rs.getString("receiver"),
                                rs.getLong("elapsed_ms"),
                                rs.getLong("zone_ms"),
                                rs.getBoolean("foul"),
                                rs.getLong("server_completed_at")),
                        raceId, teamKey, legNo)
                .stream()
                .findFirst();
    }

    /** 写入交接记录（每队每棒次仅一条，重复由主键拒绝）。 */
    public void insertRelayHandoff(RelayHandoffRow row) {
        jdbcTemplate.update(
                "INSERT INTO relay_handoff "
                        + "(race_id, team_key, leg_no, receiver, elapsed_ms, zone_ms, foul, server_completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.raceId(), row.teamKey(), row.legNo(), row.receiver(),
                row.elapsedMs(), row.zoneMs(), row.foul(), row.serverCompletedAt());
    }

    /** 写入犯规记录（不可逆，每队每交接至多一条）。 */
    public void insertRelayFoul(RelayFoulRow row) {
        jdbcTemplate.update(
                "INSERT INTO relay_foul (race_id, team_key, leg_no, zone_ms, limit_ms, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.raceId(), row.teamKey(), row.legNo(), row.zoneMs(), row.limitMs(), row.createdAt());
    }

    /** 查询赛事全部犯规记录，按记录时间排列。 */
    public List<RelayFoulRow> findRelayFouls(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, leg_no, zone_ms, limit_ms, created_at "
                        + "FROM relay_foul WHERE race_id = ? ORDER BY created_at, team_key, leg_no",
                (rs, rowNum) -> new RelayFoulRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        rs.getInt("leg_no"),
                        rs.getLong("zone_ms"),
                        rs.getLong("limit_ms"),
                        rs.getLong("created_at")),
                raceId);
    }

    /** 统计某队犯规次数。 */
    public int countRelayFouls(String raceId, String teamKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM relay_foul WHERE race_id = ? AND team_key = ?",
                Integer.class, raceId, teamKey);
        return count == null ? 0 : count;
    }

    /** 写入队伍接力完赛记录（末棒交接后自动生成，每队仅一条）。 */
    public void insertRelayFinish(RelayFinishRow row) {
        jdbcTemplate.update(
                "INSERT INTO relay_finish (race_id, team_key, total_ms, foul_count, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                row.raceId(), row.teamKey(), row.totalMs(), row.foulCount(),
                row.status().name(), row.createdAt());
    }

    /** 查询赛事全部完赛记录。 */
    public List<RelayFinishRow> findRelayFinishes(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, team_key, total_ms, foul_count, status, created_at "
                        + "FROM relay_finish WHERE race_id = ? ORDER BY total_ms, team_key",
                (rs, rowNum) -> new RelayFinishRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        rs.getLong("total_ms"),
                        rs.getInt("foul_count"),
                        com.example.starter.race.domain.EntryStatus.valueOf(rs.getString("status")),
                        rs.getLong("created_at")),
                raceId);
    }

    /** 查询某队完赛记录。 */
    public Optional<RelayFinishRow> findRelayFinish(String raceId, String teamKey) {
        return jdbcTemplate
                .query("SELECT race_id, team_key, total_ms, foul_count, status, created_at "
                                + "FROM relay_finish WHERE race_id = ? AND team_key = ?",
                        (rs, rowNum) -> new RelayFinishRow(
                                rs.getString("race_id"),
                                rs.getString("team_key"),
                                rs.getLong("total_ms"),
                                rs.getInt("foul_count"),
                                com.example.starter.race.domain.EntryStatus.valueOf(rs.getString("status")),
                                rs.getLong("created_at")),
                        raceId, teamKey)
                .stream()
                .findFirst();
    }

    /** 原子写入接力封榜快照头表、队伍名次行与逐棒明细行。 */
    public void insertRelaySnapshot(RelaySnapshotRow snapshot) {
        jdbcTemplate.update(
                "INSERT INTO relay_snapshot (race_id, version, sealed_at) VALUES (?, ?, ?)",
                snapshot.raceId(), snapshot.version(), snapshot.sealedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO relay_snapshot_team "
                        + "(race_id, team_key, rank_no, status, total_ms, foul_count, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.teams(),
                snapshot.teams().size(),
                (ps, team) -> {
                    ps.setString(1, team.raceId());
                    ps.setString(2, team.teamKey());
                    ps.setObject(3, team.rank());
                    ps.setString(4, team.status().name());
                    ps.setObject(5, team.totalMs());
                    ps.setInt(6, team.foulCount());
                    ps.setInt(7, team.displayOrder());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO relay_snapshot_leg "
                        + "(race_id, team_key, leg_no, runner, elapsed_ms, split_ms, zone_ms, foul) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.legs(),
                snapshot.legs().size(),
                (ps, leg) -> {
                    ps.setString(1, leg.raceId());
                    ps.setString(2, leg.teamKey());
                    ps.setInt(3, leg.legNo());
                    ps.setString(4, leg.runner());
                    ps.setObject(5, leg.elapsedMs());
                    ps.setObject(6, leg.splitMs());
                    ps.setObject(7, leg.zoneMs());
                    ps.setBoolean(8, leg.foul());
                });
    }

    /** 查询接力封榜快照（含队伍名次与逐棒明细）；未封榜返回 empty。 */
    public Optional<RelaySnapshotRow> findRelaySnapshot(String raceId) {
        List<RelaySnapshotRow> headers = jdbcTemplate.query(
                "SELECT race_id, version, sealed_at FROM relay_snapshot WHERE race_id = ?",
                (rs, rowNum) -> new RelaySnapshotRow(
                        rs.getString("race_id"),
                        rs.getInt("version"),
                        rs.getLong("sealed_at"),
                        List.of(),
                        List.of()),
                raceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        RelaySnapshotRow header = headers.getFirst();
        List<RelaySnapshotTeamRow> teams = jdbcTemplate.query(
                "SELECT race_id, team_key, rank_no, status, total_ms, foul_count, display_order "
                        + "FROM relay_snapshot_team WHERE race_id = ? ORDER BY display_order",
                (rs, rowNum) -> new RelaySnapshotTeamRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        (Integer) rs.getObject("rank_no"),
                        com.example.starter.race.domain.EntryStatus.valueOf(rs.getString("status")),
                        (Long) rs.getObject("total_ms"),
                        rs.getInt("foul_count"),
                        rs.getInt("display_order")),
                raceId);
        List<RelaySnapshotLegRow> legs = jdbcTemplate.query(
                "SELECT race_id, team_key, leg_no, runner, elapsed_ms, split_ms, zone_ms, foul "
                        + "FROM relay_snapshot_leg WHERE race_id = ? ORDER BY team_key, leg_no",
                (rs, rowNum) -> new RelaySnapshotLegRow(
                        rs.getString("race_id"),
                        rs.getString("team_key"),
                        rs.getInt("leg_no"),
                        rs.getString("runner"),
                        (Long) rs.getObject("elapsed_ms"),
                        (Long) rs.getObject("split_ms"),
                        (Long) rs.getObject("zone_ms"),
                        rs.getBoolean("foul")),
                raceId);
        return Optional.of(new RelaySnapshotRow(
                header.raceId(), header.version(), header.sealedAt(), teams, legs));
    }

    private static final class RaceRowMapper implements RowMapper<RaceRow> {
        @Override
        public RaceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new RaceRow(
                    rs.getString("race_id"),
                    rs.getInt("version"),
                    RaceStatus.valueOf(rs.getString("status")),
                    rs.getLong("created_at"));
        }
    }

    private static final class RunnerRowMapper implements RowMapper<RunnerRow> {
        @Override
        public RunnerRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long finishTimeMs = (Long) rs.getObject("finish_time_ms");
            return new RunnerRow(
                    rs.getLong("id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    finishTimeMs,
                    rs.getLong("created_at"),
                    rs.getLong("updated_at"));
        }
    }

    private static final class PenaltyRowMapper implements RowMapper<PenaltyRow> {
        @Override
        public PenaltyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PenaltyRow(
                    rs.getString("penalty_id"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    PenaltyType.valueOf(rs.getString("type")),
                    (Long) rs.getObject("amount_ms"),
                    rs.getBoolean("revoked"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("revoked_at"));
        }
    }

    private static final class SnapshotEntryRowMapper implements RowMapper<SnapshotEntryRow> {
        @Override
        public SnapshotEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SnapshotEntryRow(
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    (Integer) rs.getObject("rank_no"),
                    EntryStatus.valueOf(rs.getString("status")),
                    (Long) rs.getObject("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    (Long) rs.getObject("total_time_ms"),
                    rs.getInt("display_order"));
        }
    }

    private static final class IdempotencyRowMapper implements RowMapper<IdempotencyRow> {
        @Override
        public IdempotencyRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new IdempotencyRow(
                    rs.getString("request_id"),
                    rs.getString("operation"),
                    rs.getString("request_digest"),
                    rs.getInt("response_status"),
                    rs.getString("response_body"),
                    rs.getLong("created_at"));
        }
    }
}
