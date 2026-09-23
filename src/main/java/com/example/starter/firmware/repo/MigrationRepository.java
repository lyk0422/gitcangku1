package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.MigrationItem;
import com.example.starter.firmware.domain.MigrationOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 迁移单与明细数据访问。migration_key 全局唯一；明细记录迁移前后队列与指令代次。
 */
@Repository
public class MigrationRepository {

    private static final RowMapper<MigrationOrder> ORDER_MAPPER = (rs, rowNum) -> new MigrationOrder(
            rs.getLong("id"), rs.getString("migration_key"), rs.getLong("campaign_id"),
            rs.getString("status"), rs.getInt("device_count"), rs.getString("request_id"));

    private static final RowMapper<MigrationItem> ITEM_MAPPER = (rs, rowNum) -> new MigrationItem(
            rs.getLong("id"), rs.getLong("migration_id"), rs.getString("device_id"),
            rs.getLong("from_cohort_id"), rs.getLong("to_cohort_id"),
            rs.getInt("from_generation"), rs.getInt("to_generation"),
            (Long) rs.getObject("superseded_command_id"), (Long) rs.getObject("new_command_id"));

    private static final String ORDER_COLUMNS = "id, migration_key, campaign_id, status,"
            + " device_count, request_id";

    private static final String ITEM_COLUMNS = "id, migration_id, device_id, from_cohort_id,"
            + " to_cohort_id, from_generation, to_generation, superseded_command_id, new_command_id";

    private final JdbcTemplate jdbc;

    public MigrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(String migrationKey, long campaignId, int deviceCount, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO migration_order (migration_key, campaign_id, status, device_count,"
                            + " request_id) VALUES (?, ?, 'ACTIVATED', ?, ?)",
                    new String[]{"id"});
            ps.setString(1, migrationKey);
            ps.setLong(2, campaignId);
            ps.setInt(3, deviceCount);
            ps.setString(4, requestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertItem(long migrationId, String deviceId, long fromCohortId, long toCohortId,
                           int fromGeneration, int toGeneration,
                           Long supersededCommandId, Long newCommandId) {
        jdbc.update("INSERT INTO migration_item (migration_id, device_id, from_cohort_id,"
                        + " to_cohort_id, from_generation, to_generation, superseded_command_id,"
                        + " new_command_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                migrationId, deviceId, fromCohortId, toCohortId, fromGeneration, toGeneration,
                supersededCommandId, newCommandId);
    }

    public Optional<MigrationOrder> findOrderByKey(String migrationKey) {
        return jdbc.query("SELECT " + ORDER_COLUMNS + " FROM migration_order WHERE migration_key = ?",
                ORDER_MAPPER, migrationKey).stream().findFirst();
    }

    public List<MigrationItem> findItems(long migrationId) {
        return jdbc.query("SELECT " + ITEM_COLUMNS + " FROM migration_item"
                + " WHERE migration_id = ? ORDER BY id", ITEM_MAPPER, migrationId);
    }
}
