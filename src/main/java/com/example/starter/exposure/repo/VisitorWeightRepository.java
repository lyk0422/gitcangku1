package com.example.starter.exposure.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 访客滚动权重数据访问。权重不设独立账目行，而是由预占单与公告权重实时求和：
 * 窗口为 申请时刻 &gt; now - 窗口长度 且 &lt;= now，计入 CONFIRMED 与未过期 RESERVED；
 * 旧类型公告（attention_weight 为 NULL）不计入。visitor_weight_guard 行用于
 * SELECT ... FOR UPDATE 串行化同一访客跨公告的并发申请与过期回收，保证共享同一
 * 权重边界、不超卖。所有方法必须在事务内调用。
 */
@Repository
public class VisitorWeightRepository {

    private final JdbcTemplate jdbc;

    public VisitorWeightRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 行锁该访客的权重守护行；行不存在则先创建再锁。
     * 并发首建冲突时忽略唯一键异常后重试加锁（胜出事务回滚的极端情况下重试有限次）。
     */
    public void lockGuard(String visitorId, long nowUtc) {
        for (int attempt = 0; attempt < 3; attempt++) {
            ensureGuardRow(visitorId, nowUtc);
            Boolean locked = jdbc.query("SELECT visitor_id FROM visitor_weight_guard "
                            + "WHERE visitor_id = ? FOR UPDATE",
                    rs -> rs.next() ? Boolean.TRUE : null, visitorId);
            if (locked != null) {
                return;
            }
        }
        throw new IllegalStateException("weight guard row missing for visitor " + visitorId);
    }

    /**
     * 计算该访客在窗口 (now - windowMillis, now] 内已占用的权重和：
     * 跨全部新类型公告，计入 CONFIRMED 与未过期（expires_at_utc &gt; now）的 RESERVED。
     */
    public int sumWindowWeight(String visitorId, long windowStartExclusiveUtc, long nowUtc) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(c.attention_weight), 0) FROM exposure_reservation r "
                        + "JOIN campaign c ON c.campaign_id = r.campaign_id "
                        + "WHERE r.visitor_id = ? "
                        + "AND r.created_at_utc > ? AND r.created_at_utc <= ? "
                        + "AND (r.status = 'CONFIRMED' "
                        + "     OR (r.status = 'RESERVED' AND r.expires_at_utc > ?))",
                Long.class, visitorId, windowStartExclusiveUtc, nowUtc, nowUtc);
        return sum == null ? 0 : sum.intValue();
    }

    /** 确保守护行存在；并发首建冲突时行已存在即达成目的，直接忽略。 */
    private void ensureGuardRow(String visitorId, long nowUtc) {
        try {
            jdbc.update("INSERT INTO visitor_weight_guard (visitor_id, created_at_utc) "
                            + "SELECT ?, ? WHERE NOT EXISTS ("
                            + "SELECT 1 FROM visitor_weight_guard WHERE visitor_id = ?)",
                    visitorId, nowUtc, visitorId);
        } catch (DuplicateKeyException ignored) {
            // 并发事务已创建该访客守护行：目的已达成，继续加锁即可
        }
    }
}
