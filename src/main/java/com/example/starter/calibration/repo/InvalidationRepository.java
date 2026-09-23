package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ImpactPathNode;
import com.example.starter.calibration.model.InvalidationOrder;
import com.example.starter.calibration.model.InvalidationStatus;
import com.example.starter.calibration.model.SnapshotItem;

/**
 * 失效单持久化：失效单（requestId 与 invalidationKey 均唯一）、双人确认、闭包快照与冻结血缘路径。
 * 快照在创建时写入，激活时整体重算比对；影响路径按 impactVersion 只读重现。
 */
@Repository
public class InvalidationRepository {

    private static final RowMapper<InvalidationOrder> ORDER_MAPPER = (rs, rowNum) -> new InvalidationOrder(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("invalidation_key"),
            rs.getLong("root_version_id"),
            JdbcTimes.fromDb(rs.getObject("effective_from", LocalDateTime.class)),
            rs.getLong("expected_version"),
            rs.getString("reason"),
            rs.getString("created_by"),
            InvalidationStatus.valueOf(rs.getString("status")),
            rs.getString("impact_version"),
            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
            JdbcTimes.fromDb(rs.getObject("activated_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public InvalidationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入失效单并返回生成 ID。
     */
    public long insertOrder(InvalidationOrder order, long snapshotVersion) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO invalidation_order "
                            + "(invalidation_key, request_id, root_version_id, effective_from, expected_version, "
                            + "reason, status, impact_version, snapshot_version, created_by, created_at, activated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, order.invalidationKey());
            ps.setString(2, order.requestId());
            ps.setLong(3, order.rootVersionId());
            ps.setObject(4, JdbcTimes.toDb(order.invalidFrom()));
            ps.setLong(5, order.expectedVersion());
            ps.setString(6, order.reason());
            ps.setString(7, order.status().name());
            ps.setString(8, order.impactVersion());
            ps.setLong(9, snapshotVersion);
            ps.setString(10, order.createdBy());
            ps.setObject(11, JdbcTimes.toDb(order.createdAt()));
            ps.setObject(12, JdbcTimes.toDb(order.activatedAt()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 按 requestId 查询失效单（不加锁），用于幂等重放。
     */
    public Optional<InvalidationOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE request_id = ?", ORDER_MAPPER, requestId)
                .stream().findFirst();
    }

    /**
     * 按业务键查询失效单（不加锁）。
     */
    public Optional<InvalidationOrder> findByKey(String invalidationKey) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE invalidation_key = ?",
                ORDER_MAPPER, invalidationKey).stream().findFirst();
    }

    /**
     * 按影响版本号查询失效单（不加锁），用于影响查询只读重现。
     */
    public Optional<InvalidationOrder> findByImpactVersion(String impactVersion) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE impact_version = ?",
                ORDER_MAPPER, impactVersion).stream().findFirst();
    }

    /**
     * 按业务键查询失效单并加行锁（须在事务内调用），用于确认与激活串行化。
     */
    public Optional<InvalidationOrder> findByKeyForUpdate(String invalidationKey) {
        return jdbc.query("SELECT * FROM invalidation_order WHERE invalidation_key = ? FOR UPDATE",
                ORDER_MAPPER, invalidationKey).stream().findFirst();
    }

    /**
     * 写入闭包快照项（创建时，按稳定排序顺序）。
     */
    public void insertSnapshotItems(long invalidationId, List<SnapshotItem> items) {
        int ordinal = 0;
        for (SnapshotItem item : items) {
            jdbc.update("INSERT INTO invalidation_snapshot "
                            + "(invalidation_id, item_type, ref_id, ref_status, ordinal) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    invalidationId, item.itemType(), item.refId(), item.refStatus(), ordinal++);
        }
    }

    /**
     * 读取创建时的完整闭包快照（按稳定排序序号升序）。
     */
    public List<SnapshotItem> findSnapshotItems(long invalidationId) {
        return jdbc.query(
                "SELECT item_type, ref_id, ref_status FROM invalidation_snapshot "
                        + "WHERE invalidation_id = ? ORDER BY ordinal",
                (rs, rowNum) -> new SnapshotItem(
                        rs.getString("item_type"),
                        rs.getLong("ref_id"),
                        rs.getString("ref_status")),
                invalidationId);
    }

    /**
     * 追加一名确认人；同一失效单同一人重复确认抛出 DuplicateKeyException。
     */
    public void insertConfirmation(long invalidationId, String confirmedBy, Instant confirmedAt)
            throws DuplicateKeyException {
        jdbc.update("INSERT INTO invalidation_confirmation (invalidation_id, confirmed_by, confirmed_at) "
                        + "VALUES (?, ?, ?)",
                invalidationId, confirmedBy, JdbcTimes.toDb(confirmedAt));
    }

    /**
     * 统计失效单当前确认人数。
     */
    public int countConfirmations(long invalidationId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invalidation_confirmation WHERE invalidation_id = ?",
                Integer.class, invalidationId);
        return count == null ? 0 : count;
    }

    /**
     * 查询失效单全部确认人（按确认先后）。
     */
    public List<String> findConfirmerIds(long invalidationId) {
        return jdbc.queryForList(
                "SELECT confirmed_by FROM invalidation_confirmation WHERE invalidation_id = ? ORDER BY id",
                String.class, invalidationId);
    }

    /**
     * 激活成功：写入影响版本号与激活时间，状态置为 ACTIVATED。
     */
    public void markActivated(long invalidationId, String impactVersion, Instant activatedAt) {
        jdbc.update("UPDATE invalidation_order SET status = 'ACTIVATED', impact_version = ?, activated_at = ? "
                        + "WHERE id = ?",
                impactVersion, JdbcTimes.toDb(activatedAt), invalidationId);
    }

    /**
     * 冻结一条测量结果的血缘路径：按深度顺序写入路径节点（须在激活事务内调用）。
     */
    public void insertImpactPath(ImpactPathNode root, List<Long> pathVersionIds) {
        int depth = 0;
        for (Long versionId : pathVersionIds) {
            jdbc.update("INSERT INTO impact_path (impact_version, measurement_id, version_id, depth) "
                            + "VALUES (?, ?, ?, ?)",
                    root.impactVersion(), root.measurementId(), versionId, depth++);
        }
    }

    /**
     * 按影响版本号查询全部冻结路径（先按测量 ID，再按深度），用于影响查询只读重现。
     */
    public List<ImpactPathNode> findImpactPaths(String impactVersion) {
        return jdbc.query(
                "SELECT impact_version, measurement_id, depth, version_id FROM impact_path "
                        + "WHERE impact_version = ? ORDER BY measurement_id, depth",
                (rs, rowNum) -> new ImpactPathNode(
                        rs.getString("impact_version"),
                        rs.getLong("measurement_id"),
                        rs.getInt("depth"),
                        rs.getLong("version_id")),
                impactVersion);
    }
}
