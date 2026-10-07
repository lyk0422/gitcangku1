package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ReservationGroup;
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
public class GroupRepository {

    private static final RowMapper<ReservationGroup> MAPPER = (rs, rowNum) -> new ReservationGroup(
            rs.getString("group_id"),
            rs.getString("visitor_id"),
            rs.getDate("utc_date"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            rs.getLong("expires_at_utc"),
            (Long) rs.getObject("terminal_at_utc"));

    private static final String COLUMNS =
            "group_id, visitor_id, utc_date, status, created_at_utc, expires_at_utc, terminal_at_utc";

    private final JdbcTemplate jdbc;

    public GroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(ReservationGroup group) {
        jdbc.update("INSERT INTO exposure_group (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                group.groupId(),
                group.visitorId(),
                group.utcDate(),
                group.status().name(),
                group.createdAtUtc(),
                group.expiresAtUtc(),
                group.terminalAtUtc());
    }

    public Optional<ReservationGroup> findById(String groupId) {
        List<ReservationGroup> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_group WHERE group_id = ?",
                MAPPER, groupId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取组合组。
     */
    public Optional<ReservationGroup> lockById(String groupId) {
        List<ReservationGroup> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_group WHERE group_id = ? FOR UPDATE",
                MAPPER, groupId);
        return list.stream().findFirst();
    }

    /**
     * 行锁读取仍处于 RESERVED 且已到期（now &gt;= expires_at_utc）的组合组。
     */
    public Optional<ReservationGroup> lockExpiredReserved(String groupId, long nowUtc) {
        List<ReservationGroup> list = jdbc.query(
                "SELECT " + COLUMNS + " FROM exposure_group "
                        + "WHERE group_id = ? AND status = 'RESERVED' AND expires_at_utc <= ? FOR UPDATE",
                MAPPER, groupId, nowUtc);
        return list.stream().findFirst();
    }

    /**
     * 条件 CAS：仅当当前状态为 expect 时改为 target 并记录终态时刻。
     *
     * @return 是否更新成功（并发终态竞争时只有一个返回 true）
     */
    public boolean compareAndSetStatus(String groupId, ReservationStatus expect,
                                       ReservationStatus target, long terminalAtUtc) {
        int rows = jdbc.update("UPDATE exposure_group SET status = ?, terminal_at_utc = ? "
                        + "WHERE group_id = ? AND status = ?",
                target.name(), terminalAtUtc, groupId, expect.name());
        return rows == 1;
    }
}
