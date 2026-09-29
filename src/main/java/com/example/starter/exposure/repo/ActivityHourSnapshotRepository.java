package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.ActivityHourSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 活动小时日结快照数据访问。快照一旦写入即固化，后续配置变更不改写；
 * 查询方法只读。
 */
@Repository
public class ActivityHourSnapshotRepository {

    private final JdbcTemplate jdbc;

    public ActivityHourSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ActivityHourSnapshot> MAPPER = new RowMapper<>() {
        @Override
        public ActivityHourSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ActivityHourSnapshot(
                    rs.getString("activity_id"),
                    rs.getDate("utc_date").toLocalDate(),
                    rs.getInt("utc_hour"),
                    rs.getInt("confirmed_count"),
                    rs.getInt("cancelled_count"),
                    rs.getInt("expired_count"),
                    (Integer) rs.getObject("config_version"),
                    rs.getLong("settled_at_utc"));
        }
    };

    /** 行锁读取某小时快照；不存在返回 empty。 */
    public Optional<ActivityHourSnapshot> lock(String activityId, LocalDate utcDate, int utcHour) {
        return jdbc.query("SELECT activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, "
                        + "expired_count, config_version, settled_at_utc FROM activity_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? AND utc_hour = ? FOR UPDATE",
                        MAPPER, activityId, java.sql.Date.valueOf(utcDate), utcHour)
                .stream()
                .findFirst();
    }

    /** 查询活动某日全部 24 小时快照（缺失小时不返回），按小时升序。 */
    public List<ActivityHourSnapshot> findByDay(String activityId, LocalDate utcDate) {
        return jdbc.query("SELECT activity_id, utc_date, utc_hour, confirmed_count, cancelled_count, "
                        + "expired_count, config_version, settled_at_utc FROM activity_hour_snapshot "
                        + "WHERE activity_id = ? AND utc_date = ? ORDER BY utc_hour",
                        MAPPER, activityId, java.sql.Date.valueOf(utcDate));
    }

    /** 写入某小时固化快照。 */
    public void insert(ActivityHourSnapshot snapshot) {
        jdbc.update("INSERT INTO activity_hour_snapshot (activity_id, utc_date, utc_hour, "
                        + "confirmed_count, cancelled_count, expired_count, config_version, settled_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.activityId(),
                java.sql.Date.valueOf(snapshot.utcDate()),
                snapshot.utcHour(),
                snapshot.confirmedCount(),
                snapshot.cancelledCount(),
                snapshot.expiredCount(),
                snapshot.configVersion(),
                snapshot.settledAtUtc());
    }
}
