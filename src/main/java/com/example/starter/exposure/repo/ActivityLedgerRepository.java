package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityHourLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 活动小时额度账目数据访问。加锁/增减方法必须在事务内调用，
 * 依赖 SELECT ... FOR UPDATE 行锁与 CHECK 非负约束保证并发下不超卖、不变负。
 */
@Repository
public class ActivityLedgerRepository {

    private final JdbcTemplate jdbc;

    public ActivityLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ActivityHourLedger> MAPPER = (rs, rowNum) -> new ActivityHourLedger(
            rs.getString("activity_id"),
            rs.getDate("utc_date"),
            rs.getInt("hour_of_day"),
            rs.getInt("reserved_count"),
            rs.getInt("confirmed_count"),
            rs.getInt("cancelled_count"),
            rs.getInt("expired_count"));

    private static final String COLUMNS =
            "activity_id, utc_date, hour_of_day, reserved_count, confirmed_count, "
                    + "cancelled_count, expired_count";

    /**
     * 确保小时账目行存在（不存在则以全 0 创建）。
     */
    public void ensureHourRow(String activityId, LocalDate utcDate, int hourOfDay) {
        jdbc.update("INSERT INTO activity_hour_ledger "
                        + "(activity_id, utc_date, hour_of_day, reserved_count, confirmed_count, "
                        + "cancelled_count, expired_count) "
                        + "SELECT ?, ?, ?, 0, 0, 0, 0 WHERE NOT EXISTS ("
                        + "SELECT 1 FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?)",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay,
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
    }

    /**
     * 行锁读取小时账目；账目行不存在返回 empty。
     */
    public Optional<ActivityHourLedger> lockHour(String activityId, LocalDate utcDate, int hourOfDay) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ? FOR UPDATE",
                        MAPPER, activityId, java.sql.Date.valueOf(utcDate), hourOfDay)
                .stream()
                .findFirst();
    }

    /**
     * 读取某 UTC 日全部已存在的小时账目，按 hour_of_day 升序；纯读取。
     */
    public List<ActivityHourLedger> listHours(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT " + COLUMNS + " FROM activity_hour_ledger "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY hour_of_day",
                MAPPER, activityId, java.sql.Date.valueOf(utcDate));
    }

    /**
     * 预占占用 +1（reserved_count）。调用方须已持行锁并完成预算校验。
     */
    public void addReserved(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger SET reserved_count = reserved_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /**
     * 确认结算：reserved -1，confirmed +1。仅未确认预占可确认，CHECK 约束保证不变负。
     */
    public void settleConfirm(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, confirmed_count = confirmed_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /**
     * 取消结算：reserved -1，cancelled +1。仅未确认预占可取消。
     */
    public void settleCancel(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, cancelled_count = cancelled_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }

    /**
     * 过期结算：reserved -1，expired +1。仅未确认预占可过期。
     */
    public void settleExpire(String activityId, LocalDate utcDate, int hourOfDay) {
        int rows = jdbc.update("UPDATE activity_hour_ledger "
                        + "SET reserved_count = reserved_count - 1, expired_count = expired_count + 1 "
                        + "WHERE activity_id = ? AND utc_date = ? AND hour_of_day = ?",
                activityId, java.sql.Date.valueOf(utcDate), hourOfDay);
        if (rows != 1) {
            throw new IllegalStateException("activity hour ledger row missing for "
                    + activityId + " " + utcDate + " " + hourOfDay);
        }
    }
}
