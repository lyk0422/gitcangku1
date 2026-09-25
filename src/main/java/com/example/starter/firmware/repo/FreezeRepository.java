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
 * 冻结令数据访问。冻结/撤销/发布启动/拉取/回执统一先对 ACTIVE 冻结令按 id 加行锁（FOR UPDATE），
 * 形成一致提交顺序；enforced_version 记录当前已完成冻结扫荡的版本，修订后按新版本再次扫荡。
 */
@Repository
public class FreezeRepository {

    private static final RowMapper<FreezeOrder> MAPPER = (rs, rowNum) -> new FreezeOrder(
            rs.getLong("id"), rs.getInt("version"), FreezeStatus.valueOf(rs.getString("status")),
            rs.getString("start_utc"), rs.getString("end_utc"),
            splitCsv(rs.getString("scope_models")), splitIds(rs.getString("scope_release_ids")),
            (Integer) rs.getObject("enforced_version"), rs.getString("revoked_at_utc"));

    private static final String COLUMNS = "id, version, status, start_utc, end_utc, scope_models,"
            + " scope_release_ids, enforced_version, revoked_at_utc";

    private final JdbcTemplate jdbc;

    public FreezeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 锁定全局顺序闩单行。所有冻结相关写事务先取此锁，再锁 ACTIVE 冻结令，保证统一锁序与提交顺序。
     */
    public void lockGuard() {
        jdbc.queryForObject("SELECT id FROM freeze_guard_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public long insert(String startUtc, String endUtc, List<String> models, List<Long> releaseIds) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO freeze_order (version, status, start_utc, end_utc, scope_models,"
                            + " scope_release_ids) VALUES (1, 'ACTIVE', ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, startUtc);
            ps.setString(2, endUtc);
            ps.setString(3, String.join(",", models));
            ps.setString(4, joinIds(releaseIds));
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

    /**
     * 锁定全部 ACTIVE 冻结令（按 id 升序），供事务内冻结扫荡与冲突判定，调用方据此与并发写串行。
     */
    public List<FreezeOrder> findActiveForUpdate() {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE status = 'ACTIVE'"
                + " ORDER BY id FOR UPDATE", MAPPER);
    }

    public List<FreezeOrder> findActive() {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order WHERE status = 'ACTIVE' ORDER BY id",
                MAPPER);
    }

    public List<FreezeOrder> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM freeze_order ORDER BY id", MAPPER);
    }

    /**
     * 标记某版本冻结扫荡已完成；调用方持有该冻结令行锁。
     */
    public void markEnforced(long id, int version) {
        jdbc.update("UPDATE freeze_order SET enforced_version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                version, id);
    }

    /**
     * 修订：仅当版本匹配且仍 ACTIVE 时生效，版本加一、窗口与范围快照整体替换、扫荡版本重置为空。
     *
     * @return 影响行数；0 表示版本不符或已撤销
     */
    public int revise(long id, int expectedVersion, String startUtc, String endUtc,
                      List<String> models, List<Long> releaseIds) {
        int rows = jdbc.update("UPDATE freeze_order SET version = version + 1, start_utc = ?, end_utc = ?,"
                        + " scope_models = ?, scope_release_ids = ?, enforced_version = NULL,"
                        + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND version = ? AND status = 'ACTIVE'",
                startUtc, endUtc, String.join(",", models), joinIds(releaseIds), id, expectedVersion);
        return rows;
    }

    /**
     * 撤销：仅 ACTIVE 可撤销，返回影响行数。撤销不复活已冻结任务，只放行撤销后新发起的任务。
     */
    public int revoke(long id, String revokedAtUtc) {
        return jdbc.update("UPDATE freeze_order SET status = 'REVOKED', revoked_at_utc = ?,"
                + " updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'ACTIVE'", revokedAtUtc, id);
    }

    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(csv.split(","));
    }

    static List<Long> splitIds(String csv) {
        if (csv == null || csv.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).map(Long::valueOf).toList();
    }

    private static String joinIds(List<Long> values) {
        return String.join(",", values.stream().map(String::valueOf).toList());
    }
}
