package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.GateStatus;
import com.example.starter.firmware.domain.ReleaseGate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 正式投放门禁数据访问。uk_gate_version 保证同一 型号|目标版本|回退版本 至多一条 READY_FOR_RELEASE。
 * 门禁结论只增不改；新目标版本没有对应门禁，必须重新演练。
 */
@Repository
public class GateRepository {

    private static final RowMapper<ReleaseGate> MAPPER = (rs, rowNum) -> new ReleaseGate(
            rs.getLong("id"), rs.getString("model"), rs.getString("target_version"),
            rs.getString("rollback_version"), GateStatus.valueOf(rs.getString("status")),
            rs.getLong("rehearsal_id"), rs.getString("operator"), rs.getString("granted_at_utc"));

    private static final String COLUMNS = "id, model, target_version, rollback_version, status,"
            + " rehearsal_id, operator, granted_at_utc";

    private final JdbcTemplate jdbc;

    public GateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String model, String targetVersion, String rollbackVersion, long rehearsalId,
                       String operator, String grantedAtUtc) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_gate (model, target_version, rollback_version, status,"
                            + " rehearsal_id, operator, granted_at_utc)"
                            + " VALUES (?, ?, ?, 'READY_FOR_RELEASE', ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, model);
            ps.setString(2, targetVersion);
            ps.setString(3, rollbackVersion);
            ps.setLong(4, rehearsalId);
            ps.setString(5, operator);
            ps.setString(6, grantedAtUtc);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ReleaseGate> findByVersions(String model, String targetVersion, String rollbackVersion) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_gate"
                        + " WHERE model = ? AND target_version = ? AND rollback_version = ?",
                MAPPER, model, targetVersion, rollbackVersion).stream().findFirst();
    }

    public Optional<ReleaseGate> findByRehearsal(long rehearsalId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM release_gate WHERE rehearsal_id = ?",
                MAPPER, rehearsalId).stream().findFirst();
    }

    /**
     * 诊断列表：型号必填，目标/回退版本可选过滤；按门禁ID稳定排序。
     */
    public List<ReleaseGate> findByFilters(String model, String targetVersion, String rollbackVersion) {
        StringBuilder sql = new StringBuilder("SELECT ").append(COLUMNS)
                .append(" FROM release_gate WHERE model = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(model));
        if (targetVersion != null) {
            sql.append(" AND target_version = ?");
            args.add(targetVersion);
        }
        if (rollbackVersion != null) {
            sql.append(" AND rollback_version = ?");
            args.add(rollbackVersion);
        }
        sql.append(" ORDER BY id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }
}
