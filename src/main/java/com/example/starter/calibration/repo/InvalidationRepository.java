package com.example.starter.calibration.repo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.InvalidationConfirmation;
import com.example.starter.calibration.model.InvalidationOrder;
import com.example.starter.calibration.model.InvalidationStatus;

/**
 * 失效单与双人确认持久化。invalidation_key 与 request_id 均全局唯一；
 * 激活状态流转在持有失效单行锁与血缘全局锁的事务内完成。
 */
@Repository
public class InvalidationRepository {

    private static final RowMapper<InvalidationOrder> ORDER_MAPPER = (rs, rowNum) -> new InvalidationOrder(
            rs.getLong("id"),
            rs.getString("invalidation_key"),
            rs.getString("request_id"),
            rs.getString("root_standard_id"),
            JdbcTimes.fromDb(rs.getObject("invalid_from", LocalDateTime.class)),
            rs.getInt("expected_version"),
            rs.getString("reason"),
            rs.getString("created_by"),
            InvalidationStatus.valueOf(rs.getString("status")),
            rs.getString("impact_version"),
            rs.getString("closure_snapshot"),
            rs.getString("request_params"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("activated_at", LocalDateTime.class)));

    private static final RowMapper<InvalidationConfirmation> CONFIRMATION_MAPPER =
            (rs, rowNum) -> new InvalidationConfirmation(
                    rs.getLong("id"),
                    rs.getString("invalidation_key"),
                    rs.getString("confirmed_by"),
                    JdbcTimes.fromDb(rs.getObject("confirmed_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public InvalidationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入失效单；invalidation_key 或 request_id 冲突时抛出 DuplicateKeyException。
     */
    public void insertOrder(InvalidationOrder order) {
        jdbc.update("INSERT INTO invalidation_order "
                        + "(invalidation_key, request_id, root_standard_id, invalid_from, expected_version, "
                        + "reason, created_by, status, closure_snapshot, request_params, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                order.invalidationKey(),
                order.requestId(),
                order.rootStandardId(),
                JdbcTimes.toDb(order.invalidFrom()),
                order.expectedVersion(),
                order.reason(),
                order.createdBy(),
                order.status().name(),
                order.closureSnapshot(),
                order.requestParams(),
                JdbcTimes.toDb(order.createdAt()));
    }

    /**
     * 按业务键查询失效单（不加锁）。
     */
    public Optional<InvalidationOrder> findByKey(String invalidationKey) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE invalidation_key = ?",
                        ORDER_MAPPER, invalidationKey)
                .stream().findFirst();
    }

    /**
     * 按业务键查询并加行锁（须在事务内调用），用于确认与激活串行化。
     */
    public Optional<InvalidationOrder> findByKeyForUpdate(String invalidationKey) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE invalidation_key = ? FOR UPDATE",
                        ORDER_MAPPER, invalidationKey)
                .stream().findFirst();
    }

    /**
     * 按幂等请求键查询失效单。
     */
    public Optional<InvalidationOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE request_id = ?",
                        ORDER_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按影响版本号查询已激活失效单（影响查询只读重现）。
     */
    public Optional<InvalidationOrder> findByImpactVersion(String impactVersion) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE impact_version = ?",
                        ORDER_MAPPER, impactVersion)
                .stream().findFirst();
    }

    /**
     * 激活失效单：置为 ACTIVATED 并写入影响版本号与激活时间（须在持有行锁的事务内调用）。
     */
    public void markActivated(String invalidationKey, String impactVersion, Instant activatedAt) {
        jdbc.update("UPDATE invalidation_order SET status = ?, impact_version = ?, activated_at = ? "
                        + "WHERE invalidation_key = ?",
                InvalidationStatus.ACTIVATED.name(), impactVersion,
                JdbcTimes.toDb(activatedAt), invalidationKey);
    }

    /**
     * 追加一条确认记录；同一失效单同一确认人重复时抛出 DuplicateKeyException。
     */
    public void insertConfirmation(String invalidationKey, String confirmedBy, Instant confirmedAt) {
        jdbc.update("INSERT INTO invalidation_confirmation (invalidation_key, confirmed_by, confirmed_at) "
                        + "VALUES (?, ?, ?)",
                invalidationKey, confirmedBy, JdbcTimes.toDb(confirmedAt));
    }

    /**
     * 查询某失效单的全部确认记录（按确认人升序，保证输出稳定）。
     */
    public List<InvalidationConfirmation> findConfirmations(String invalidationKey) {
        return jdbc.query("SELECT * FROM invalidation_confirmation WHERE invalidation_key = ? "
                + "ORDER BY confirmed_by", CONFIRMATION_MAPPER, invalidationKey);
    }
}
