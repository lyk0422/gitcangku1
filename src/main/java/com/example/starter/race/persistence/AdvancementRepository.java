package com.example.starter.race.persistence;

import com.example.starter.race.domain.AdvancementEntryType;
import com.example.starter.race.domain.AdvancementListStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 分组划分与晋级名单的 JDBC 仓储；写方法均假定运行在业务事务内，
 * 并发定序由 {@code race} 行写锁与条件版本更新保证（见 RaceServiceImpl）。
 */
@Repository
public class AdvancementRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdvancementRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 一次性写入分组定义与成员归属（划分后不可改写）。 */
    public void insertGroups(
            List<AdvancementGroupRow> groups, List<AdvancementGroupMemberRow> members) {
        jdbcTemplate.batchUpdate(
                "INSERT INTO advancement_group (race_id, group_code, position, version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                groups,
                groups.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.groupCode());
                    ps.setInt(3, row.position());
                    ps.setInt(4, row.version());
                    ps.setLong(5, row.createdAt());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO advancement_group_member (race_id, group_code, bib) "
                        + "VALUES (?, ?, ?)",
                members,
                members.size(),
                (ps, row) -> {
                    ps.setString(1, row.raceId());
                    ps.setString(2, row.groupCode());
                    ps.setString(3, row.bib());
                });
    }

    /** 查询赛事全部分组，按 position 升序；未划分时为空列表。 */
    public List<AdvancementGroupRow> findGroups(String raceId) {
        return jdbcTemplate.query(
                "SELECT race_id, group_code, position, version, created_at "
                        + "FROM advancement_group WHERE race_id = ? ORDER BY position",
                new AdvancementGroupRowMapper(), raceId);
    }

    /** 查询赛事全部分组成员，按分组顺序与参赛号字典序排列。 */
    public List<AdvancementGroupMemberRow> findMembers(String raceId) {
        return jdbcTemplate.query(
                "SELECT m.race_id, m.group_code, m.bib "
                        + "FROM advancement_group_member m "
                        + "JOIN advancement_group g ON g.race_id = m.race_id "
                        + "AND g.group_code = m.group_code "
                        + "WHERE m.race_id = ? ORDER BY g.position, m.bib",
                new AdvancementGroupMemberRowMapper(), raceId);
    }

    /** 原子写入名单头表、不可变晋级条目与未晋级清单。 */
    public void insertAdvancementList(AdvancementListRow row) {
        jdbcTemplate.update(
                "INSERT INTO advancement_list "
                        + "(advancement_key, race_id, version, status, direct_quota, wildcard_quota, "
                        + "request_id, generated_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                row.advancementKey(), row.raceId(), row.version(), row.status().name(),
                row.directQuota(), row.wildcardQuota(), row.requestId(), row.generatedAt());
        jdbcTemplate.batchUpdate(
                "INSERT INTO advancement_list_entry "
                        + "(advancement_key, race_id, bib, group_code, rank_no, entry_type, "
                        + "finish_time_ms, penalty_ms, total_time_ms, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.entries(),
                row.entries().size(),
                (ps, entry) -> {
                    ps.setString(1, entry.advancementKey());
                    ps.setString(2, entry.raceId());
                    ps.setString(3, entry.bib());
                    ps.setString(4, entry.groupCode());
                    ps.setInt(5, entry.rank());
                    ps.setString(6, entry.type().name());
                    ps.setLong(7, entry.finishTimeMs());
                    ps.setLong(8, entry.penaltyMs());
                    ps.setLong(9, entry.totalTimeMs());
                    ps.setInt(10, entry.displayOrder());
                });
        jdbcTemplate.batchUpdate(
                "INSERT INTO advancement_list_non_advanced "
                        + "(advancement_key, race_id, bib, group_code, rank_no, "
                        + "finish_time_ms, penalty_ms, total_time_ms, display_order) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.nonAdvanced(),
                row.nonAdvanced().size(),
                (ps, entry) -> {
                    ps.setString(1, entry.advancementKey());
                    ps.setString(2, entry.raceId());
                    ps.setString(3, entry.bib());
                    ps.setString(4, entry.groupCode());
                    ps.setInt(5, entry.rank());
                    ps.setLong(6, entry.finishTimeMs());
                    ps.setLong(7, entry.penaltyMs());
                    ps.setLong(8, entry.totalTimeMs());
                    ps.setInt(9, entry.displayOrder());
                });
    }

    /** 按全局键查询名单头表（含已撤销）；不存在返回 empty。 */
    public Optional<AdvancementListRow> findAdvancementHeader(String advancementKey) {
        return jdbcTemplate
                .query("SELECT advancement_key, race_id, version, status, direct_quota, "
                                + "wildcard_quota, request_id, generated_at, revoked_at "
                                + "FROM advancement_list WHERE advancement_key = ?",
                        new AdvancementListRowMapper(), advancementKey)
                .stream()
                .findFirst();
    }

    /** 查询赛事当前生效名单（含全部条目与未晋级清单）；无生效名单返回 empty。 */
    public Optional<AdvancementListRow> findActiveAdvancement(String raceId) {
        List<AdvancementListRow> headers = jdbcTemplate.query(
                "SELECT advancement_key, race_id, version, status, direct_quota, wildcard_quota, "
                        + "request_id, generated_at, revoked_at "
                        + "FROM advancement_list WHERE race_id = ? AND status = 'ACTIVE'",
                new AdvancementListRowMapper(), raceId);
        if (headers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(loadDetails(headers.getFirst()));
    }

    /** 查询赛事全部名单（生效与已撤销，按生成时刻升序），含条目与未晋级清单。 */
    public List<AdvancementListRow> findAdvancementHistory(String raceId) {
        return jdbcTemplate.query(
                "SELECT advancement_key, race_id, version, status, direct_quota, wildcard_quota, "
                        + "request_id, generated_at, revoked_at "
                        + "FROM advancement_list WHERE race_id = ? ORDER BY generated_at",
                new AdvancementListRowMapper(), raceId)
                .stream()
                .map(this::loadDetails)
                .toList();
    }

    private AdvancementListRow loadDetails(AdvancementListRow header) {
        List<AdvancementEntryRow> entries = jdbcTemplate.query(
                "SELECT advancement_key, race_id, bib, group_code, rank_no, entry_type, "
                        + "finish_time_ms, penalty_ms, total_time_ms, display_order "
                        + "FROM advancement_list_entry WHERE advancement_key = ? "
                        + "ORDER BY display_order",
                new AdvancementEntryRowMapper(), header.advancementKey());
        List<AdvancementNonAdvancedRow> nonAdvanced = jdbcTemplate.query(
                "SELECT advancement_key, race_id, bib, group_code, rank_no, "
                        + "finish_time_ms, penalty_ms, total_time_ms, display_order "
                        + "FROM advancement_list_non_advanced WHERE advancement_key = ? "
                        + "ORDER BY display_order",
                new AdvancementNonAdvancedRowMapper(), header.advancementKey());
        return new AdvancementListRow(
                header.advancementKey(), header.raceId(), header.version(), header.status(),
                header.directQuota(), header.wildcardQuota(), header.requestId(),
                header.generatedAt(), header.revokedAt(), entries, nonAdvanced);
    }

    /**
     * 条件撤销：仅当名单仍 ACTIVE 时改为 REVOKED 并记录版本与撤销时刻。
     *
     * @return 受影响行数；0 表示名单不存在或已撤销
     */
    public int revokeIfActive(String advancementKey, int newVersion, long now) {
        return jdbcTemplate.update(
                "UPDATE advancement_list SET status = 'REVOKED', version = ?, revoked_at = ? "
                        + "WHERE advancement_key = ? AND status = 'ACTIVE'",
                newVersion, now, advancementKey);
    }

    private static final class AdvancementGroupRowMapper implements RowMapper<AdvancementGroupRow> {
        @Override
        public AdvancementGroupRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AdvancementGroupRow(
                    rs.getString("race_id"),
                    rs.getString("group_code"),
                    rs.getInt("position"),
                    rs.getInt("version"),
                    rs.getLong("created_at"));
        }
    }

    private static final class AdvancementGroupMemberRowMapper
            implements RowMapper<AdvancementGroupMemberRow> {
        @Override
        public AdvancementGroupMemberRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AdvancementGroupMemberRow(
                    rs.getString("race_id"),
                    rs.getString("group_code"),
                    rs.getString("bib"));
        }
    }

    private static final class AdvancementListRowMapper implements RowMapper<AdvancementListRow> {
        @Override
        public AdvancementListRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AdvancementListRow(
                    rs.getString("advancement_key"),
                    rs.getString("race_id"),
                    rs.getInt("version"),
                    AdvancementListStatus.valueOf(rs.getString("status")),
                    rs.getInt("direct_quota"),
                    rs.getInt("wildcard_quota"),
                    rs.getString("request_id"),
                    rs.getLong("generated_at"),
                    (Long) rs.getObject("revoked_at"));
        }
    }

    private static final class AdvancementEntryRowMapper
            implements RowMapper<AdvancementEntryRow> {
        @Override
        public AdvancementEntryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AdvancementEntryRow(
                    rs.getString("advancement_key"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("group_code"),
                    rs.getInt("rank_no"),
                    AdvancementEntryType.valueOf(rs.getString("entry_type")),
                    rs.getLong("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    rs.getLong("total_time_ms"),
                    rs.getInt("display_order"));
        }
    }

    private static final class AdvancementNonAdvancedRowMapper
            implements RowMapper<AdvancementNonAdvancedRow> {
        @Override
        public AdvancementNonAdvancedRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AdvancementNonAdvancedRow(
                    rs.getString("advancement_key"),
                    rs.getString("race_id"),
                    rs.getString("bib"),
                    rs.getString("group_code"),
                    rs.getInt("rank_no"),
                    rs.getLong("finish_time_ms"),
                    rs.getLong("penalty_ms"),
                    rs.getLong("total_time_ms"),
                    rs.getInt("display_order"));
        }
    }
}
