package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.MigrationItem;
import com.example.starter.firmware.domain.MigrationOrder;
import com.example.starter.firmware.domain.MigrationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 跨队列迁移单数据访问。migration_key 全局唯一；预览不写数据，激活成功才在同一事务落主表与明细。
 */
@Repository
public class MigrationRepository {

    private static final RowMapper<MigrationOrder> ORDER_MAPPER = (rs, rowNum) -> new MigrationOrder(
            rs.getLong("id"), rs.getString("migration_key"), rs.getLong("release_id"),
            MigrationStatus.valueOf(rs.getString("status")), rs.getInt("device_count"),
            rs.getString("committed_at"));

    private static final RowMapper<MigrationItem> ITEM_MAPPER = (rs, rowNum) -> {
        Long oldCommandId = (Long) rs.getObject("old_command_id");
        return new MigrationItem(rs.getLong("id"), rs.getLong("migration_id"), rs.getString("device_id"),
                rs.getLong("from_cohort_id"), rs.getLong("to_cohort_id"),
                rs.getInt("expected_assignment_version"), rs.getInt("old_generation"),
                rs.getInt("new_generation"), oldCommandId, rs.getLong("new_command_id"));
    };

    private static final String ORDER_COLUMNS = "id, migration_key, release_id, status, device_count, committed_at";

    private final JdbcTemplate jdbc;

    public MigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(String migrationKey, long releaseId, int deviceCount, String committedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cohort_migration_order (migration_key, release_id, status, device_count,"
                            + " committed_at) VALUES (?, ?, 'COMMITTED', ?, ?)",
                    new String[]{"id"});
            ps.setString(1, migrationKey);
            ps.setLong(2, releaseId);
            ps.setInt(3, deviceCount);
            ps.setString(4, committedAt);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public long insertItem(long migrationId, String deviceId, long fromCohortId, long toCohortId,
                           int expectedAssignmentVersion, int oldGeneration, int newGeneration,
                           Long oldCommandId, long newCommandId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO cohort_migration_item (migration_id, device_id, from_cohort_id, to_cohort_id,"
                            + " expected_assignment_version, old_generation, new_generation, old_command_id,"
                            + " new_command_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, migrationId);
            ps.setString(2, deviceId);
            ps.setLong(3, fromCohortId);
            ps.setLong(4, toCohortId);
            ps.setInt(5, expectedAssignmentVersion);
            ps.setInt(6, oldGeneration);
            ps.setInt(7, newGeneration);
            if (oldCommandId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, oldCommandId);
            }
            ps.setLong(9, newCommandId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<MigrationOrder> findById(long id) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM cohort_migration_order WHERE id = ?",
                ORDER_MAPPER, id).stream().findFirst();
    }

    public Optional<MigrationOrder> findByKey(String migrationKey) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM cohort_migration_order WHERE migration_key = ?",
                ORDER_MAPPER, migrationKey).stream().findFirst();
    }

    public List<MigrationItem> findItemsByMigration(long migrationId) {
        return jdbc.query("SELECT id, migration_id, device_id, from_cohort_id, to_cohort_id,"
                + " expected_assignment_version, old_generation, new_generation, old_command_id, new_command_id"
                + " FROM cohort_migration_item WHERE migration_id = ? ORDER BY id", ITEM_MAPPER, migrationId);
    }
}
