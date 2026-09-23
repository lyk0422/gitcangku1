package com.example.starter.repo;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 豁免包、区域项与核销流水数据访问。
 *
 * <p>核销/撤销事务通过对 permit 行做真实更新（touch 加一）取得行级排他锁，
 * 使同一豁免包的两次核销在 H2（MVStore）与 MySQL InnoDB 下确定串行，
 * “读取余额→判定剩余>0→扣 1→写流水”整段不会被同包并发事务穿插。</p>
 */
@Repository
public class PermitRepository {

    private final JdbcTemplate jdbc;

    public PermitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String PERMIT_COLUMNS =
            "permit_id, route_id, route_version, status, version, touch, request_id, issued_at, revoked_at";

    private static final RowMapper<PermitPo> PERMIT_MAPPER = (rs, n) -> new PermitPo(
            rs.getString("permit_id"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getLong("issued_at"),
            (Long) rs.getObject("revoked_at"));

    private static final RowMapper<PermitItemPo> ITEM_MAPPER = (rs, n) -> new PermitItemPo(
            rs.getString("permit_id"),
            rs.getString("region_key"),
            rs.getLong("region_version"),
            rs.getLong("valid_from"),
            rs.getLong("valid_to"),
            rs.getInt("quota"),
            rs.getInt("remaining"));

    private static final RowMapper<PermitRedeemPo> REDEEM_MAPPER = (rs, n) -> new PermitRedeemPo(
            rs.getString("redeem_id"),
            rs.getString("permit_id"),
            rs.getString("region_key"),
            rs.getString("flight_key"),
            rs.getInt("balance_before"),
            rs.getInt("balance_after"),
            rs.getLong("review_at"),
            rs.getLong("created_at"));

    /** 按 permitKey 查询豁免包，不存在返回 null。 */
    public PermitPo findPermit(String permitId) {
        try {
            return jdbc.queryForObject(
                    "SELECT " + PERMIT_COLUMNS + " FROM permit WHERE permit_id = ?",
                    PERMIT_MAPPER, permitId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    /**
     * 在当前事务内对豁免包行做真实更新（touch 加一）取得行级写锁，再读取豁免包。
     * 更新为不同值确保 H2/MySQL 不跳过加锁；锁持有至事务提交/回滚。
     *
     * @return 豁免包当前状态；不存在（更新 0 行）返回 null
     */
    public PermitPo findPermitForUpdate(String permitId) {
        int locked = jdbc.update(
                "UPDATE permit SET touch = touch + 1 WHERE permit_id = ?", permitId);
        if (locked == 0) {
            return null;
        }
        return findPermit(permitId);
    }

    /** 创建豁免包（初始 ACTIVE、版本 1；调用方负责事务）。 */
    public void insertPermit(PermitPo permit) {
        jdbc.update("INSERT INTO permit "
                        + "(permit_id, route_id, route_version, status, version, touch, "
                        + "request_id, issued_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                permit.permitId(), permit.routeId(), permit.routeVersion(), permit.status(),
                permit.version(), 0L, permit.requestId(), permit.issuedAt(), permit.revokedAt());
    }

    /**
     * 撤销豁免包：仅当当前 ACTIVE 时置为 REVOKED、版本推进到 2（同时推进 touch
     * 以持有行写锁，与核销事务互斥）。
     *
     * @return 更新行数；0 表示不存在或已撤销
     */
    public int markRevoked(String permitId, long revokedAt) {
        return jdbc.update(
                "UPDATE permit SET status = 'REVOKED', version = 2, touch = touch + 1, revoked_at = ? "
                        + "WHERE permit_id = ? AND status = 'ACTIVE'",
                revokedAt, permitId);
    }

    /** 查询豁免包全部区域项（按 regionKey 排序，结果稳定）。 */
    public List<PermitItemPo> findItems(String permitId) {
        return jdbc.query(
                "SELECT permit_id, region_key, region_version, valid_from, valid_to, quota, remaining "
                        + "FROM permit_item WHERE permit_id = ? ORDER BY region_key",
                ITEM_MAPPER, permitId);
    }

    /** 批量写入豁免包区域项（调用方负责事务；包内 regionKey 唯一由主键约束）。 */
    public void insertItems(List<PermitItemPo> items) {
        for (PermitItemPo item : items) {
            jdbc.update("INSERT INTO permit_item "
                            + "(permit_id, region_key, region_version, valid_from, valid_to, quota, remaining) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    item.permitId(), item.regionKey(), item.regionVersion(),
                    item.validFrom(), item.validTo(), item.quota(), item.remaining());
        }
    }

    /**
     * 查询绑定指定航线精确版本的全部豁免包（含 ACTIVE 与 REVOKED），按 permitId 排序。
     * 审核事务会对这些包逐一加行锁：含 REVOKED 是为了在缺陷结果中区分
     * “缺项(MISSING)”与“包已撤销(REVOKED)”，并与并发撤销严格串行。
     */
    public List<PermitPo> findPermitsForRoute(String routeId, int routeVersion) {
        return jdbc.query(
                "SELECT " + PERMIT_COLUMNS + " FROM permit "
                        + "WHERE route_id = ? AND route_version = ? "
                        + "ORDER BY permit_id",
                PERMIT_MAPPER, routeId, routeVersion);
    }

    /**
     * 条件扣减区域项额度：仅当剩余额度为正时扣 1。
     * 必须在持有该豁免包行写锁的事务内调用；返回 0 表示额度已耗尽。
     *
     * @return 更新行数
     */
    public int decrementRemaining(String permitId, String regionKey) {
        return jdbc.update(
                "UPDATE permit_item SET remaining = remaining - 1 "
                        + "WHERE permit_id = ? AND region_key = ? AND remaining > 0",
                permitId, regionKey);
    }

    /** 追加一条核销流水（只追加；调用方负责事务）。 */
    public void insertRedeem(PermitRedeemPo po) {
        jdbc.update("INSERT INTO permit_redeem "
                        + "(redeem_id, permit_id, region_key, flight_key, balance_before, "
                        + "balance_after, review_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.redeemId(), po.permitId(), po.regionKey(), po.flightKey(),
                po.balanceBefore(), po.balanceAfter(), po.reviewAt(), po.createdAt());
    }

    /** 按豁免包查询全部核销流水（按落库时间、流水标识正序）。 */
    public List<PermitRedeemPo> findRedeemsByPermit(String permitId) {
        return jdbc.query(
                "SELECT redeem_id, permit_id, region_key, flight_key, balance_before, "
                        + "balance_after, review_at, created_at "
                        + "FROM permit_redeem WHERE permit_id = ? "
                        + "ORDER BY created_at, redeem_id",
                REDEEM_MAPPER, permitId);
    }
}
