package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 豁免包、区域项与核销流水数据访问。
 */
@Repository
public class PermitRepository {

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PermitPackagePo> PACKAGE_MAPPER = (rs, n) -> new PermitPackagePo(
            rs.getString("permit_key"),
            rs.getInt("route_version"),
            rs.getString("status"),
            rs.getInt("permit_version"),
            rs.getString("request_id"),
            (Long) rs.getObject("revoked_at"),
            rs.getLong("created_at"),
            rs.getLong("touch"));

    private static final RowMapper<PermitItemPo> ITEM_MAPPER = (rs, n) -> new PermitItemPo(
            rs.getString("permit_key"),
            rs.getString("region_key"),
            rs.getLong("region_version"),
            rs.getLong("valid_from"),
            rs.getLong("valid_to"),
            rs.getInt("quota"),
            rs.getInt("remaining"),
            rs.getLong("touch"));

    private static final RowMapper<PermitConsumptionPo> CONSUMPTION_MAPPER = (rs, n) ->
            new PermitConsumptionPo(
                    rs.getString("consumption_id"),
                    rs.getString("review_id"),
                    rs.getString("flight_key"),
                    rs.getString("permit_key"),
                    rs.getString("region_key"),
                    rs.getLong("region_version"),
                    rs.getInt("balance_before"),
                    rs.getInt("balance_after"),
                    rs.getInt("seq"),
                    rs.getLong("created_at"));

    private static final String PACKAGE_COLUMNS =
            "permit_key, route_version, status, permit_version, request_id, "
                    + "revoked_at, created_at, touch";

    private static final String ITEM_COLUMNS =
            "permit_key, region_key, region_version, valid_from, valid_to, quota, remaining, touch";

    /** 按 permitKey 查询豁免包，不存在返回 null。 */
    public PermitPackagePo findPackage(String permitKey) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + PACKAGE_COLUMNS + " FROM permit_package WHERE permit_key = ?",
                    PACKAGE_MAPPER, permitKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 对豁免包行做真实更新加行级排他锁并读取，行不存在返回 null。 */
    public PermitPackagePo findPackageForUpdate(String permitKey) {
        int locked = jdbc.update(
                "UPDATE permit_package SET touch = touch + 1 WHERE permit_key = ?", permitKey);
        if (locked == 0) {
            return null;
        }
        return findPackage(permitKey);
    }

    /**
     * 发现绑定指定航线版本且未撤销的豁免包（不加锁），按 permitKey 升序返回。
     * 调用方须随后对每个包执行 {@link #findPackageForUpdate} 以持有行级排他锁，
     * 避免使用发现后、加锁前被并发撤销的豁免包。
     */
    public List<PermitPackagePo> findIssuedPackagesByRouteVersion(int routeVersion) {
        return jdbc.query(
                "SELECT " + PACKAGE_COLUMNS + " FROM permit_package "
                        + "WHERE route_version = ? AND status = 'ISSUED' ORDER BY permit_key",
                PACKAGE_MAPPER, routeVersion);
    }

    /** 插入豁免包（调用方负责事务）。 */
    public void insertPackage(PermitPackagePo po) {
        jdbc.update("INSERT INTO permit_package "
                        + "(permit_key, route_version, status, permit_version, request_id, "
                        + "revoked_at, created_at, touch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.permitKey(), po.routeVersion(), po.status(), po.permitVersion(),
                po.requestId(), po.revokedAt(), po.createdAt(), po.touch());
    }

    /** 撤销豁免包（调用方负责事务与加锁）。 */
    public void markRevoked(String permitKey, long revokedAt) {
        jdbc.update("UPDATE permit_package SET status = 'REVOKED', revoked_at = ? "
                + "WHERE permit_key = ? AND status = 'ISSUED'", revokedAt, permitKey);
    }

    /** 查询豁免包全部区域项，按 regionKey 升序。 */
    public List<PermitItemPo> findItems(String permitKey) {
        return jdbc.query(
                "SELECT " + ITEM_COLUMNS + " FROM permit_item WHERE permit_key = ? "
                        + "ORDER BY region_key",
                ITEM_MAPPER, permitKey);
    }

    /**
     * 对豁免包的全部区域项行做真实更新以持有行级排他锁（调用方须已按 permitKey
     * 升序持有所属豁免包行锁），保证后续额度读取与扣减来自提交时一致状态，
     * 并与并发审核核销串行化。
     */
    public void lockItems(String permitKey) {
        jdbc.update("UPDATE permit_item SET touch = touch + 1 WHERE permit_key = ?", permitKey);
    }

    /** 对指定区域项行做真实更新加行级排他锁并读取，不存在返回 null。 */
    public PermitItemPo findItemForUpdate(String permitKey, String regionKey) {
        int locked = jdbc.update(
                "UPDATE permit_item SET touch = touch + 1 WHERE permit_key = ? AND region_key = ?",
                permitKey, regionKey);
        if (locked == 0) {
            return null;
        }
        try {
            return jdbc.queryForObject(
                    "SELECT " + ITEM_COLUMNS + " FROM permit_item "
                            + "WHERE permit_key = ? AND region_key = ?",
                    ITEM_MAPPER, permitKey, regionKey);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /** 插入区域项（调用方负责事务）。 */
    public void insertItem(PermitItemPo po) {
        jdbc.update("INSERT INTO permit_item "
                        + "(permit_key, region_key, region_version, valid_from, valid_to, "
                        + "quota, remaining, touch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.permitKey(), po.regionKey(), po.regionVersion(), po.validFrom(), po.validTo(),
                po.quota(), po.remaining(), po.touch());
    }

    /**
     * 条件扣减 1 次额度：仅当 remaining &gt; 0 时扣减。
     *
     * @return 更新行数；0 表示已耗尽
     */
    public int decrementRemaining(String permitKey, String regionKey) {
        return jdbc.update("UPDATE permit_item SET remaining = remaining - 1, touch = touch + 1 "
                + "WHERE permit_key = ? AND region_key = ? AND remaining > 0", permitKey, regionKey);
    }

    /** 插入核销流水（调用方负责事务）。 */
    public void insertConsumption(PermitConsumptionPo po) {
        jdbc.update("INSERT INTO permit_consumption "
                        + "(consumption_id, review_id, flight_key, permit_key, region_key, "
                        + "region_version, balance_before, balance_after, seq, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.consumptionId(), po.reviewId(), po.flightKey(), po.permitKey(),
                po.regionKey(), po.regionVersion(), po.balanceBefore(), po.balanceAfter(),
                po.seq(), po.createdAt());
    }

    /** 查询某豁免包全部核销流水（按创建时间、seq 升序）。 */
    public List<PermitConsumptionPo> findConsumptionsByPermit(String permitKey) {
        return jdbc.query(
                "SELECT consumption_id, review_id, flight_key, permit_key, region_key, "
                        + "region_version, balance_before, balance_after, seq, created_at "
                        + "FROM permit_consumption WHERE permit_key = ? ORDER BY created_at, seq",
                CONSUMPTION_MAPPER, permitKey);
    }

    /** 查询某航班审核触发的全部核销流水（按 seq 升序）。 */
    public List<PermitConsumptionPo> findConsumptionsByReview(String reviewId) {
        return jdbc.query(
                "SELECT consumption_id, review_id, flight_key, permit_key, region_key, "
                        + "region_version, balance_before, balance_after, seq, created_at "
                        + "FROM permit_consumption WHERE review_id = ? ORDER BY seq",
                CONSUMPTION_MAPPER, reviewId);
    }
}
