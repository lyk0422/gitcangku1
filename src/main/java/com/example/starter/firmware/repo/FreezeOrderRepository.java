package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.FreezeOrder;
import com.example.starter.firmware.domain.FreezeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 冻结令数据访问。freeze_key 全局唯一；范围集合以规范化排序后的逗号分隔串存储。
 */
@Repository
public class FreezeOrderRepository {

    private static final RowMapper<FreezeOrder> MAPPER = (rs, rowNum) -> new FreezeOrder(
            rs.getLong("id"), rs.getInt("version"), rs.getString("freeze_key"),
            toList(rs.getString("models_csv")), toLongList(rs.getString("release_ids_csv")),
            rs.getString("start_utc"), rs.getString("end_utc"),
            FreezeStatus.valueOf(rs.getString("status")),
            rs.getString("exception_incident_id"), toNullableList(rs.getString("exception_approvers_csv")),
            rs.getString("revoked_at_utc"), toNullableLongList(rs.getString("revoke_affected_task_ids_csv")));

    private static final String COLUMNS = "id, version, freeze_key, models_csv, release_ids_csv,"
            + " start_utc, end_utc, status, exception_incident_id, exception_approvers_csv,"
            + " revoked_at_utc, revoke_affected_task_ids_csv";

    private final JdbcTemplate jdbc;

    public FreezeOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String freezeKey, String modelsCsv, String releaseIdsCsv,
                       String startUtc, String endUtc,
                       String exceptionIncidentId, String exceptionApproversCsv) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO freeze_order (version, freeze_key, models_csv, release_ids_csv,"
                            + " start_utc, end_utc, status, exception_incident_id, exception_approvers_csv)"
                            + " VALUES (1, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    new String[]{"id"});
            ps.setString(1, freezeKey);
            ps.setString(2, modelsCsv);
            ps.setString(3, releaseIdsCsv);
            ps.setString(4, startUtc);
            ps.setString(5, endUtc);
            ps.setString(6, exceptionIncidentId);
            ps.setString(7, exceptionApproversCsv);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<FreezeOrder> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FreezeOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<FreezeOrder> findByFreezeKey(String freezeKey) {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE freeze_key = ?", MAPPER, freezeKey)
                .stream().findFirst();
    }

    public List<FreezeOrder> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order ORDER BY id", MAPPER);
    }

    public List<FreezeOrder> findByStatus(FreezeStatus status) {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE status = ? ORDER BY id",
                MAPPER, status.name());
    }

    /**
     * 修订：仅当版本匹配且仍 ACTIVE 时生效，版本加一，返回影响行数。
     */
    public int revise(long id, int expectedVersion, String modelsCsv, String releaseIdsCsv,
                      String startUtc, String endUtc,
                      String exceptionIncidentId, String exceptionApproversCsv) {
        return jdbc.update("UPDATE freeze_order SET version = version + 1, models_csv = ?, release_ids_csv = ?,"
                        + " start_utc = ?, end_utc = ?, exception_incident_id = ?, exception_approvers_csv = ?,"
                        + " updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                modelsCsv, releaseIdsCsv, startUtc, endUtc, exceptionIncidentId, exceptionApproversCsv,
                id, expectedVersion);
    }

    /**
     * 撤销：仅当仍 ACTIVE 时生效，记录撤销时刻与解冻任务ID，返回影响行数。
     */
    public int revoke(long id, String revokedAtUtc, String affectedTaskIdsCsv) {
        return jdbc.update("UPDATE freeze_order SET status = 'REVOKED', revoked_at_utc = ?,"
                        + " revoke_affected_task_ids_csv = ?, updated_at = CURRENT_TIMESTAMP"
                        + " WHERE id = ? AND status = 'ACTIVE'",
                revokedAtUtc, affectedTaskIdsCsv, id);
    }

    static List<String> toList(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(csv.split(","));
    }

    static List<Long> toLongList(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
    }

    private static List<String> toNullableList(String csv) {
        return csv == null ? null : toList(csv);
    }

    private static List<Long> toNullableLongList(String csv) {
        return csv == null ? null : toLongList(csv);
    }
}
