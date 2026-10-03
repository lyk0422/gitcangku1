package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityHourLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * 活动小时额度账本数据访问。加锁/增减方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK (used &gt;= 0) 约束保证并发不超卖、不变负。
 */
@Repository
public class ActivityHourLedgerRepository {

    private static final RowMapper<ActivityHourLedger> MAPPER = (rs, rowNum) -> new ActivityHourLedger(
            rs.getString("activity_id"),
            rs.getDate("utc_date").toLocalDate(),
            rs.getInt("hour_of_day"),
            rs.getInt("used"),
            rs.getInt("confirmed"),
            rs.getInt("cancelled"));

    private final JdbcTemplate jdbc;

    public ActivityHourLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保账本行存在（不存在则以 0 创建）。 */
    public void ensureRow(String activityId, LocalDate utcDate, int hourOfDay) {
        jdbc.update("INSERT INTO activity_hour_ledger (activity_id, utc_date, hour_of_day, used) "
                        + "SELECT ?, ?, ?, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }

    /** 行锁读取当前占用数；账本行不存在视为 0。 */
    public int lockUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        Integer value = jdbc.query("SELECT used FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? FOR UPDATE",
                rs -> rs.next() ? rs.getInt(1) : null,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        return value == null ? 0 : value;
    }

    /** 占用一次小时额度（+1）。调用方须已持行锁并完成容量校验。 */
    public void addUsed(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET used = used + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 结算确认：该小时确认数快照 +1，占用不变（CONFIRMED 仍占用当日额度）。 */
    public void markConfirmed(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET confirmed = confirmed + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 结算取消/过期：占用 -1 且取消数快照 +1；CHECK 约束保证不变负。 */
    public void markCancelled(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET used = used - 1, cancelled = cancelled + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /** 查询某日全部小时账本，按小时升序；无账本行的小时不出现在结果中。 */
    public List<ActivityHourLedger> listByDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT activity_id, utc_date, hour_of_day, used, confirmed, cancelled "
                        + "FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                MAPPER, activityId, java.sql.Date.valueOf(utcDate));
    }
}