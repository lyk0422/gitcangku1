package com.example.starter.exposure.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 访客互斥锁数据访问。名单变更（加入/解除）与访客相关曝光操作
 * （申请/确认/取消/查询结算）必须在事务内先取得对应访客的行锁，
 * 使同一访客的抑制变更与预占终态竞争严格串行化。
 *
 * <p>全局加锁顺序：额度总账行 -> 访客锁 -> 预占单行 -> 访客额度账行，
 * 多把同类锁按键排序获取，避免死锁。</p>
 */
@Repository
public class VisitorLockRepository {

    private final JdbcTemplate jdbc;

    public VisitorLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 确保访客锁行存在（不存在则创建），不取锁。 */
    public void ensure(String visitorId) {
        jdbc.update("INSERT INTO visitor_lock (visitor_id) "
                        + "SELECT ? WHERE NOT EXISTS (SELECT 1 FROM visitor_lock WHERE visitor_id = ?)",
                visitorId, visitorId);
    }

    /** 行锁锁定访客；调用前须先 {@link #ensure(String)}。必须在事务内调用。 */
    public void lock(String visitorId) {
        Integer found = jdbc.query(
                "SELECT 1 FROM visitor_lock WHERE visitor_id = ? FOR UPDATE",
                rs -> rs.next() ? 1 : null, visitorId);
        if (found == null) {
            throw new IllegalStateException("visitor lock row missing: " + visitorId);
        }
    }

    /**
     * 按 visitorId 字典序依次确保存在并加锁一批访客，消除并发多访客事务间的等待环。
     * 必须在事务内调用。
     */
    public void lockAll(Collection<String> visitorIds) {
        SortedSet<String> sorted = new TreeSet<>(visitorIds);
        for (String visitorId : sorted) {
            ensure(visitorId);
            lock(visitorId);
        }
    }
}
