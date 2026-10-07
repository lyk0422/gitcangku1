package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Pool;
import com.example.starter.exposure.domain.PoolMember;
import com.example.starter.exposure.domain.PoolSelection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 缺额轮转选择池数据访问：池、成员（含已提交缺额）、选择记录与临时缺额快照。
 * 加锁方法必须在事务内调用。
 */
@Repository
public class PoolRepository {

    /**
     * 选择时冻结的各成员临时缺额快照行。
     *
     * @param reservationId  关联预占单编号
     * @param campaignId     成员公告编号
     * @param pendingDeficit 选择时计算的临时缺额；仅确认时提交，取消/到期丢弃
     */
    public record DeficitRow(String reservationId, String campaignId, long pendingDeficit) {
    }

    private static final RowMapper<Pool> POOL_MAPPER = (rs, rowNum) -> new Pool(
            rs.getString("pool_id"),
            rs.getInt("pool_version"),
            rs.getString("created_by"),
            rs.getLong("created_at_utc"));

    private static final RowMapper<PoolMember> MEMBER_MAPPER = (rs, rowNum) -> new PoolMember(
            rs.getString("pool_id"),
            rs.getString("campaign_id"),
            rs.getInt("weight"),
            rs.getLong("committed_deficit"));

    private static final RowMapper<PoolSelection> SELECTION_MAPPER = (rs, rowNum) -> new PoolSelection(
            rs.getString("reservation_id"),
            rs.getString("pool_id"),
            rs.getString("campaign_id"),
            rs.getString("visitor_id"),
            rs.getLong("created_at_utc"));

    private static final RowMapper<DeficitRow> DEFICIT_MAPPER = (rs, rowNum) -> new DeficitRow(
            rs.getString("reservation_id"),
            rs.getString("campaign_id"),
            rs.getLong("pending_deficit"));

    private final JdbcTemplate jdbc;

    public PoolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPool(Pool pool) {
        jdbc.update("INSERT INTO selection_pool (pool_id, pool_version, created_by, created_at_utc) "
                        + "VALUES (?, ?, ?, ?)",
                pool.poolId(), pool.poolVersion(), pool.createdBy(), pool.createdAtUtc());
    }

    public Optional<Pool> findPoolById(String poolId) {
        return jdbc.query("SELECT pool_id, pool_version, created_by, created_at_utc "
                        + "FROM selection_pool WHERE pool_id = ?", POOL_MAPPER, poolId)
                .stream()
                .findFirst();
    }

    /** 行锁读取选择池；用于串行化同一池的并发轮转选择。 */
    public Optional<Pool> lockPoolById(String poolId) {
        return jdbc.query("SELECT pool_id, pool_version, created_by, created_at_utc "
                        + "FROM selection_pool WHERE pool_id = ? FOR UPDATE", POOL_MAPPER, poolId)
                .stream()
                .findFirst();
    }

    public void insertMember(PoolMember member) {
        jdbc.update("INSERT INTO pool_member (pool_id, campaign_id, weight, committed_deficit) "
                        + "VALUES (?, ?, ?, ?)",
                member.poolId(), member.campaignId(), member.weight(), member.committedDeficit());
    }

    /** 按公告编号字典序返回池成员，保证选择遍历顺序确定。 */
    public List<PoolMember> findMembers(String poolId) {
        return jdbc.query("SELECT pool_id, campaign_id, weight, committed_deficit "
                        + "FROM pool_member WHERE pool_id = ? ORDER BY campaign_id",
                MEMBER_MAPPER, poolId);
    }

    /** 更新成员已提交缺额；仅在关联预占确认时调用。 */
    public void updateCommittedDeficit(String poolId, String campaignId, long committedDeficit) {
        int rows = jdbc.update("UPDATE pool_member SET committed_deficit = ? "
                        + "WHERE pool_id = ? AND campaign_id = ?",
                committedDeficit, poolId, campaignId);
        if (rows != 1) {
            throw new IllegalStateException("pool member missing for " + poolId + " " + campaignId);
        }
    }

    public void insertSelection(PoolSelection selection) {
        jdbc.update("INSERT INTO pool_selection "
                        + "(reservation_id, pool_id, campaign_id, visitor_id, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?)",
                selection.reservationId(), selection.poolId(), selection.campaignId(),
                selection.visitorId(), selection.createdAtUtc());
    }

    public Optional<PoolSelection> findSelectionByReservation(String reservationId) {
        return jdbc.query("SELECT reservation_id, pool_id, campaign_id, visitor_id, created_at_utc "
                        + "FROM pool_selection WHERE reservation_id = ?", SELECTION_MAPPER, reservationId)
                .stream()
                .findFirst();
    }

    /** 查询池中当前仍处于 RESERVED 的选择（至多一条）；调用方已先结算过期预占。 */
    public Optional<PoolSelection> findActiveSelection(String poolId) {
        return jdbc.query("SELECT ps.reservation_id, ps.pool_id, ps.campaign_id, ps.visitor_id, "
                        + "ps.created_at_utc "
                        + "FROM pool_selection ps JOIN exposure_reservation r "
                        + "ON ps.reservation_id = r.reservation_id "
                        + "WHERE ps.pool_id = ? AND r.status = 'RESERVED'",
                SELECTION_MAPPER, poolId)
                .stream()
                .findFirst();
    }

    public void insertDeficit(DeficitRow row) {
        jdbc.update("INSERT INTO pool_selection_deficit (reservation_id, campaign_id, pending_deficit) "
                        + "VALUES (?, ?, ?)",
                row.reservationId(), row.campaignId(), row.pendingDeficit());
    }

    /** 按公告编号字典序返回某次选择冻结的临时缺额快照。 */
    public List<DeficitRow> findDeficits(String reservationId) {
        return jdbc.query("SELECT reservation_id, campaign_id, pending_deficit "
                        + "FROM pool_selection_deficit WHERE reservation_id = ? ORDER BY campaign_id",
                DEFICIT_MAPPER, reservationId);
    }
}
