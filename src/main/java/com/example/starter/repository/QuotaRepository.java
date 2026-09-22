package com.example.starter.repository;

import java.util.List;
import java.util.Optional;

import com.example.starter.domain.QuotaAccount;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 额度账数据访问。账户按 (scope, quotaKey, utcDate) 唯一；
 * 占用数 held_count 恒满足 0 <= held_count <= cap，由行锁与条件 UPDATE 共同保证。
 */
@Repository
public class QuotaRepository {

    private static final RowMapper<QuotaAccount> MAPPER = (rs, n) -> {
        QuotaAccount a = new QuotaAccount();
        a.setId(rs.getLong("id"));
        a.setScope(rs.getString("scope"));
        a.setQuotaKey(rs.getString("quota_key"));
        a.setUtcDate(rs.getString("utc_date"));
        a.setCap(rs.getInt("cap"));
        a.setHeldCount(rs.getInt("held_count"));
        return a;
    };

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 取当日账户并加行锁；账户不存在时按 cap 初始化（held_count=0）。
     * 并发首建通过唯一索引去重，冲突方重取加锁。
     */
    public QuotaAccount getOrCreateForUpdate(String scope, String quotaKey, String utcDate, int cap) {
        Optional<QuotaAccount> existing = findForUpdate(scope, quotaKey, utcDate);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update("""
                    INSERT INTO quota_account (scope, quota_key, utc_date, cap, held_count)
                    VALUES (?, ?, ?, ?, 0)
                    """, scope, quotaKey, utcDate, cap);
        } catch (DuplicateKeyException e) {
            // 并发首建，对方已提交/正在提交；重取并加锁。
        }
        return findForUpdate(scope, quotaKey, utcDate)
                .orElseThrow(() -> new IllegalStateException("quota account vanished after insert"));
    }

    public Optional<QuotaAccount> findForUpdate(String scope, String quotaKey, String utcDate) {
        return jdbc.query("""
                SELECT * FROM quota_account
                WHERE scope = ? AND quota_key = ? AND utc_date = ?
                FOR UPDATE
                """, MAPPER, scope, quotaKey, utcDate).stream().findFirst();
    }

    public Optional<QuotaAccount> find(String scope, String quotaKey, String utcDate) {
        return jdbc.query("""
                SELECT * FROM quota_account
                WHERE scope = ? AND quota_key = ? AND utc_date = ?
                """, MAPPER, scope, quotaKey, utcDate).stream().findFirst();
    }

    /**
     * 列出某公告某日所有访客维度账户（历史日账目可查）。
     */
    public List<QuotaAccount> findVisitorAccounts(String campaignId, String utcDate) {
        String escaped = campaignId.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query("""
                SELECT * FROM quota_account
                WHERE scope = 'VISITOR' AND utc_date = ? AND quota_key LIKE ? ESCAPE '\\'
                ORDER BY quota_key
                """, MAPPER, utcDate, escaped + ":%");
    }

    /**
     * 条件占用：held_count < cap 时 +1，返回是否成功；从根本上防止超卖。
     */
    public boolean tryHold(long id) {
        return jdbc.update("""
                UPDATE quota_account SET held_count = held_count + 1
                WHERE id = ? AND held_count < cap
                """, id) == 1;
    }

    /**
     * 释放一次占用；held_count > 0 才生效，返回是否实际释放，杜绝变负/重复释放。
     */
    public boolean release(long id) {
        return jdbc.update("""
                UPDATE quota_account SET held_count = held_count - 1
                WHERE id = ? AND held_count > 0
                """, id) == 1;
    }
}
