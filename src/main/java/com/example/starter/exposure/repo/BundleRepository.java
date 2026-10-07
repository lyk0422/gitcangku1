package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ReservationBundle;
import com.example.starter.exposure.domain.ReservationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 组合预占组数据访问。加锁/CAS 方法必须在事务内调用。
 */
@Repository
public class BundleRepository {

    private final JdbcTemplate jdbc;

    public BundleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReservationBundle> MAPPER = (rs, rowNum) -> new ReservationBundle(
            rs.getString("bundle_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "bundle_id, visitor_id, utc_date, status, version, "
                    + "created_at_utc, expires_at_utc, terminal_at_utc";

    public void insert(ReservationBundle bundle) {
        jdbc.update("INSERT INTO exposure_bundle (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                bundle.bundleId(),
                bundle.visitorId(),
                bundle.utcDate(),
                bundle.status().name(),
                bundle.version(),
                bundle.createdAtUtc(),
                bundle.expiresAtUtc(),
                bundle.terminalAtUtc());
    }

    public Optional<ReservationBundle> findById(String bundleId) {
        List<ReservationBundle> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_bundle WHERE bundle_id = ?",
                MAPPER, bundleId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取组合预占组。
     */
    public Optional<ReservationBundle> lockById(String bundleId) {
        List<ReservationBundle> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_bundle WHERE bundle_id = ? FOR UPDATE",
                MAPPER, bundleId);
        return list.stream().findFirst();
    }

    /**
     * 条件 CAS：仅当当前状态与版本均匹配时改为 target、版本 +1 并记录终态时刻。
     *
     * @return 是否更新成功（并发终态竞争时只有一个返回 true）
     */
    public boolean compareAndSetStatus(String bundleId, ReservationStatus expect,
                                       ReservationStatus target, long expectedVersion, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE exposure_bundle SET status = ?, version = version + 1, "
                        + "terminal_at_utc = ? WHERE bundle_id = ? AND status = ? AND version = ?",
                target.name(), terminalAtUtc, bundleId, expect.name(), expectedVersion);
        return rows == 1;
    }
}
