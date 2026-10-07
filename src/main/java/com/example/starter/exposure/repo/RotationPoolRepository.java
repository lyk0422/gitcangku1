package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.RotationPool;
import com.example.starter.exposure.domain.RotationPoolMember;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缺额轮转池数据访问：池行、成员（含已提交缺额）与待决临时缺额。
 * 加锁方法必须在事务内调用；池行锁是同一池并发选择的串行化点。
 */
@Repository
public class RotationPoolRepository {

    private static final RowMapper<RotationPool> POOL_MAPPER = (rs, rowNum) -> new RotationPool(
            rs.getString("pool_id"),
            rs.getInt("version"),
            rs.getString("pending_reservation_id"),
            rs.getLong("created_at_utc"));

    private static final RowMapper<RotationPoolMember> MEMBER_MAPPER = (rs, rowNum) -> new RotationPoolMember(
            rs.getString("pool_id"),
            rs.getString("campaign_id"),
            rs.getInt("weight"),
            rs.getLong("committed_deficit"));

    private final JdbcTemplate jdbc;

    public RotationPoolRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPool(RotationPool pool) {
        jdbc.update("INSERT INTO rotation_pool (pool_id, version, pending_reservation_id, created_at_utc) "
                        + "VALUES (?, ?, NULL, ?)",
                pool.poolId(), pool.version(), pool.createdAtUtc());
    }

    public void insertMember(RotationPoolMember member) {
        jdbc.update("INSERT INTO rotation_pool_member (pool_id, campaign_id, weight, committed_deficit) "
                        + "VALUES (?, ?, ?, 0)",
                member.poolId(), member.campaignId(), member.weight());
    }

    public Optional<RotationPool> findPoolById(String poolId) {
        return jdbc.query("SELECT pool_id, version, pending_reservation_id, created_at_utc "
                        + "FROM rotation_pool WHERE pool_id = ?", POOL_MAPPER, poolId)
                .stream().findFirst();
    }

    /** 行锁读取池；同一池的选择/确认/取消/到期清理借此串行化。 */
    public Optional<RotationPool> lockPoolById(String poolId) {
        return jdbc.query("SELECT pool_id, version, pending_reservation_id, created_at_utc "
                        + "FROM rotation_pool WHERE pool_id = ? FOR UPDATE", POOL_MAPPER, poolId)
                .stream().findFirst();
    }

    /** 成员列表，按公告号字典序升序（确定性选择次序）。 */
    public List<RotationPoolMember> listMembers(String poolId) {
        return jdbc.query("SELECT pool_id, campaign_id, weight, committed_deficit "
                        + "FROM rotation_pool_member WHERE pool_id = ? ORDER BY campaign_id",
                MEMBER_MAPPER, poolId);
    }

    /** 行锁读取成员，按公告号字典序升序。 */
    public List<RotationPoolMember> lockMembers(String poolId) {
        return jdbc.query("SELECT pool_id, campaign_id, weight, committed_deficit "
                        + "FROM rotation_pool_member WHERE pool_id = ? ORDER BY campaign_id FOR UPDATE",
                MEMBER_MAPPER, poolId);
    }

    /** 保存本轮选择的临时缺额（先清后插，调用方须已持池行锁）。 */
    public void replacePendingDeficits(String poolId, Map<String, Long> pendingByCampaign) {
        jdbc.update("DELETE FROM rotation_pool_pending_deficit WHERE pool_id = ?", poolId);
        for (Map.Entry<String, Long> entry : pendingByCampaign.entrySet()) {
            jdbc.update("INSERT INTO rotation_pool_pending_deficit (pool_id, campaign_id, pending_deficit) "
                            + "VALUES (?, ?, ?)",
                    poolId, entry.getKey(), entry.getValue());
        }
    }

    /** 读取临时缺额，按公告号字典序升序。 */
    public Map<String, Long> findPendingDeficits(String poolId) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT campaign_id, pending_deficit FROM rotation_pool_pending_deficit "
                        + "WHERE pool_id = ? ORDER BY campaign_id",
                rs -> {
                    result.put(rs.getString("campaign_id"), rs.getLong("pending_deficit"));
                }, poolId);
        return result;
    }

    /** 将临时缺额并入成员已提交缺额并清除临时缺额（CONFIRMED 时调用）。 */
    public void commitPendingDeficits(String poolId) {
        jdbc.update("UPDATE rotation_pool_member m SET committed_deficit = ("
                        + "SELECT p.pending_deficit FROM rotation_pool_pending_deficit p "
                        + "WHERE p.pool_id = m.pool_id AND p.campaign_id = m.campaign_id) "
                        + "WHERE m.pool_id = ? AND EXISTS ("
                        + "SELECT 1 FROM rotation_pool_pending_deficit p "
                        + "WHERE p.pool_id = m.pool_id AND p.campaign_id = m.campaign_id)",
                poolId);
        jdbc.update("DELETE FROM rotation_pool_pending_deficit WHERE pool_id = ?", poolId);
    }

    /** 丢弃临时缺额（取消/到期时调用），已提交缺额不变。 */
    public void discardPendingDeficits(String poolId) {
        jdbc.update("DELETE FROM rotation_pool_pending_deficit WHERE pool_id = ?", poolId);
    }

    /** 设置/清除待决预占单。 */
    public void updatePendingReservation(String poolId, String reservationId) {
        int rows = jdbc.update("UPDATE rotation_pool SET pending_reservation_id = ? WHERE pool_id = ?",
                reservationId, poolId);
        if (rows != 1) {
            throw new IllegalStateException("rotation pool row missing for " + poolId);
        }
    }

    /** 清除待决预占单（置 NULL）。 */
    public void clearPendingReservation(String poolId) {
        int rows = jdbc.update("UPDATE rotation_pool SET pending_reservation_id = NULL WHERE pool_id = ?",
                poolId);
        if (rows != 1) {
            throw new IllegalStateException("rotation pool row missing for " + poolId);
        }
    }
}
