package com.example.starter.exposure.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 访客注意力滚动权重数据访问。
 *
 * <p>权重不单独建账，直接由预占单与公告权重实时求和：同一访客跨全部新类型公告，
 * 归属时刻（首次申请时刻，确认不刷新）落在滚动窗口内的 CONFIRMED 与未过期 RESERVED
 * 预占的权重之和。取消/过期通过状态流转即时释放权重，确认保持权重直到移出窗口。</p>
 *
 * <p>visitor_weight_lock 每访客一行，申请新类型公告时先 SELECT ... FOR UPDATE 持有，
 * 使同一访客跨公告的并发申请与过期回收共享同一权重边界。加锁方法必须在事务内调用。</p>
 */
@Repository
public class WeightRepository {

    private final JdbcTemplate jdbc;

    public WeightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 确保访客权重锁行存在。并发首建时唯一约束冲突可安全忽略（锁行内容无状态）。
     */
    public void ensureLockRow(String visitorId) {
        try {
            jdbc.update("INSERT INTO visitor_weight_lock (visitor_id) "
                            + "SELECT ? WHERE NOT EXISTS ("
                            + "SELECT 1 FROM visitor_weight_lock WHERE visitor_id = ?)",
                    visitorId, visitorId);
        } catch (DuplicateKeyException concurrentInsert) {
            // 并发事务已创建该访客的锁行，无需处理
        }
    }

    /** 行锁持有访客权重边界；调用前须确保锁行存在，须在事务内调用。 */
    public void lockVisitor(String visitorId) {
        jdbc.query("SELECT visitor_id FROM visitor_weight_lock WHERE visitor_id = ? FOR UPDATE",
                rs -> {
                }, visitorId);
    }

    /**
     * 计算访客当前滚动窗口内已占用的注意力权重：跨全部新类型公告，
     * 归属时刻（首次申请时刻 created_at_utc）满足 {@code windowStartExclusive < t <= now}，
     * 且状态为 CONFIRMED 或未过期（expires_at_utc &gt; now）的 RESERVED。
     *
     * @param visitorId             访客编号
     * @param windowStartExclusive  窗口下界（不含），epoch 毫秒，UTC
     * @param now                   当前时刻（窗口上界，含），epoch 毫秒，UTC
     * @return 窗口内权重和，单位权重点
     */
    public int sumWindowWeight(String visitorId, long windowStartExclusive, long now) {
        Integer sum = jdbc.query("SELECT COALESCE(SUM(c.attention_weight), 0) "
                        + "FROM exposure_reservation r "
                        + "JOIN campaign c ON c.campaign_id = r.campaign_id "
                        + "WHERE r.visitor_id = ? AND c.attention_weight IS NOT NULL "
                        + "AND r.created_at_utc > ? AND r.created_at_utc <= ? "
                        + "AND (r.status = 'CONFIRMED' "
                        + "OR (r.status = 'RESERVED' AND r.expires_at_utc > ?))",
                rs -> rs.next() ? rs.getInt(1) : 0,
                visitorId, windowStartExclusive, now, now);
        return sum == null ? 0 : sum;
    }
}
