package com.example.starter.curtailment.dispatch;

import com.example.starter.curtailment.common.UtcTimes;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.example.starter.curtailment.common.UtcTimes.toUtcDateTime;

/**
 * 削减调度持久化：全部使用参数化 SQL，时间列按 UTC 读写。
 */
@Repository
public class DispatchRepository {

    private static final RowMapper<Dispatch> DISPATCH_MAPPER = (rs, rowNum) -> new Dispatch(
            rs.getLong("id"),
            rs.getString("dispatch_key"),
            rs.getString("feeder_id"),
            UtcTimes.toInstant(rs.getObject("execute_from_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("execute_to_utc", LocalDateTime.class)),
            rs.getBigDecimal("target_power_kw"),
            rs.getInt("version"),
            DispatchStatus.valueOf(rs.getString("status")),
            UtcTimes.toInstant(rs.getObject("published_at_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("cancelled_at_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("created_at_utc", LocalDateTime.class)),
            UtcTimes.toInstant(rs.getObject("updated_at_utc", LocalDateTime.class)));

    private static final RowMapper<Allocation> ALLOCATION_MAPPER = (rs, rowNum) -> new Allocation(
            rs.getLong("id"),
            rs.getLong("dispatch_id"),
            rs.getString("site_id"),
            rs.getBigDecimal("power_kw"));

    private static final RowMapper<DispatchEvent> EVENT_MAPPER = (rs, rowNum) -> new DispatchEvent(
            rs.getLong("id"),
            rs.getLong("dispatch_id"),
            rs.getString("event_type"),
            rs.getInt("version"),
            UtcTimes.toInstant(rs.getObject("created_at_utc", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public DispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dispatch insert(Dispatch dispatch) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO curtailment_dispatch (dispatch_key, feeder_id, execute_from_utc, execute_to_utc,"
                            + " target_power_kw, version, status, published_at_utc, cancelled_at_utc,"
                            + " created_at_utc, updated_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, dispatch.dispatchKey());
            ps.setString(2, dispatch.feederId());
            ps.setObject(3, toUtcDateTime(dispatch.executeFrom()));
            ps.setObject(4, toUtcDateTime(dispatch.executeTo()));
            ps.setBigDecimal(5, dispatch.targetPowerKw());
            ps.setInt(6, dispatch.version());
            ps.setString(7, dispatch.status().name());
            ps.setObject(8, dispatch.publishedAt() == null ? null : toUtcDateTime(dispatch.publishedAt()));
            ps.setObject(9, dispatch.cancelledAt() == null ? null : toUtcDateTime(dispatch.cancelledAt()));
            ps.setObject(10, toUtcDateTime(dispatch.createdAt()));
            ps.setObject(11, toUtcDateTime(dispatch.updatedAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return new Dispatch(id, dispatch.dispatchKey(), dispatch.feederId(), dispatch.executeFrom(),
                dispatch.executeTo(), dispatch.targetPowerKw(), dispatch.version(), dispatch.status(),
                dispatch.publishedAt(), dispatch.cancelledAt(), dispatch.createdAt(), dispatch.updatedAt());
    }

    public Optional<Dispatch> findByKey(String dispatchKey) {
        return jdbc.query("SELECT * FROM curtailment_dispatch WHERE dispatch_key = ?", DISPATCH_MAPPER, dispatchKey)
                .stream()
                .findFirst();
    }

    /** 按当前版本推进草稿版本；版本或状态不符时影响行数为 0。 */
    public int bumpDraftVersion(long id, int expectedVersion, int newVersion, Instant updatedAt) {
        return jdbc.update(
                "UPDATE curtailment_dispatch SET version = ?, updated_at_utc = ?"
                        + " WHERE id = ? AND version = ? AND status = 'DRAFT'",
                newVersion, toUtcDateTime(updatedAt), id, expectedVersion);
    }

    /** 草稿整体替换分配：先删后插，同事务提交。 */
    public void replaceAllocations(long dispatchId, List<Allocation> allocations) {
        jdbc.update("DELETE FROM dispatch_allocation WHERE dispatch_id = ?", dispatchId);
        insertAllocations(dispatchId, allocations);
    }

    public void insertAllocations(long dispatchId, List<Allocation> allocations) {
        jdbc.batchUpdate("INSERT INTO dispatch_allocation (dispatch_id, site_id, power_kw) VALUES (?, ?, ?)",
                allocations, allocations.size(),
                (ps, allocation) -> {
                    ps.setLong(1, dispatchId);
                    ps.setString(2, allocation.siteId());
                    ps.setBigDecimal(3, allocation.powerKw());
                });
    }

    public List<Allocation> findAllocations(long dispatchId) {
        return jdbc.query("SELECT * FROM dispatch_allocation WHERE dispatch_id = ? ORDER BY id",
                ALLOCATION_MAPPER, dispatchId);
    }

    /** 草稿发布：仅当仍处于草稿态时生效，返回影响行数。 */
    public int markPublished(long id, int newVersion, Instant publishedAt, Instant updatedAt) {
        return jdbc.update(
                "UPDATE curtailment_dispatch SET status = 'PUBLISHED', version = ?,"
                        + " published_at_utc = ?, updated_at_utc = ? WHERE id = ? AND status = 'DRAFT'",
                newVersion, toUtcDateTime(publishedAt), toUtcDateTime(updatedAt), id);
    }

    /** 已发布取消：仅当仍处于已发布态时生效，返回影响行数。 */
    public int markCancelled(long id, int newVersion, Instant cancelledAt, Instant updatedAt) {
        return jdbc.update(
                "UPDATE curtailment_dispatch SET status = 'CANCELLED', version = ?,"
                        + " cancelled_at_utc = ?, updated_at_utc = ? WHERE id = ? AND status = 'PUBLISHED'",
                newVersion, toUtcDateTime(cancelledAt), toUtcDateTime(updatedAt), id);
    }

    /** 指定站点上是否存在执行区间与给定区间重叠的已发布调度。 */
    public boolean existsPublishedOverlapOnSite(String siteId, Instant from, Instant to) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curtailment_dispatch d"
                        + " JOIN dispatch_allocation a ON a.dispatch_id = d.id"
                        + " WHERE d.status = 'PUBLISHED' AND a.site_id = ?"
                        + " AND d.execute_from_utc < ? AND d.execute_to_utc > ?",
                Integer.class, siteId, toUtcDateTime(to), toUtcDateTime(from));
        return count != null && count > 0;
    }

    /** 指定站点上所有与给定区间时间重叠的已发布调度分配功率之和；无记录时返回零。 */
    public BigDecimal sumPublishedOverlapPower(String siteId, Instant from, Instant to) {
        BigDecimal sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(a.power_kw), 0) FROM curtailment_dispatch d"
                        + " JOIN dispatch_allocation a ON a.dispatch_id = d.id"
                        + " WHERE d.status = 'PUBLISHED' AND a.site_id = ?"
                        + " AND d.execute_from_utc < ? AND d.execute_to_utc > ?",
                BigDecimal.class, siteId, toUtcDateTime(to), toUtcDateTime(from));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    /** 当前已发布调度；feederId 为空时不过滤馈线。 */
    public List<Dispatch> findPublished(String feederId) {
        if (feederId == null) {
            return jdbc.query("SELECT * FROM curtailment_dispatch WHERE status = 'PUBLISHED' ORDER BY id",
                    DISPATCH_MAPPER);
        }
        return jdbc.query("SELECT * FROM curtailment_dispatch WHERE status = 'PUBLISHED' AND feeder_id = ?"
                + " ORDER BY id", DISPATCH_MAPPER, feederId);
    }

    public void insertEvent(long dispatchId, String eventType, int version, Instant createdAt) {
        jdbc.update("INSERT INTO dispatch_event (dispatch_id, event_type, version, created_at_utc)"
                        + " VALUES (?, ?, ?, ?)",
                dispatchId, eventType, version, toUtcDateTime(createdAt));
    }

    public List<DispatchEvent> findEvents(long dispatchId) {
        return jdbc.query("SELECT * FROM dispatch_event WHERE dispatch_id = ? ORDER BY id", EVENT_MAPPER,
                dispatchId);
    }
}
