package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Consent;
import com.example.starter.exposure.domain.ConsentDecision;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客活动类别同意区间数据访问。
 *
 * <p>区间重叠与版本覆盖的裁决全部在服务层完成：提交时以
 * {@code SELECT ... FOR UPDATE} 锁定该访客+类别的候选区间后在内存判定，
 * 保证同版本区间不重叠、DENY 不被低版本覆盖。</p>
 */
@Repository
public class ConsentRepository {

    private static final String COLUMNS =
            "consent_id, visitor_id, category, decision, consent_version, "
                    + "effective_start_utc, effective_end_utc, created_at_utc";

    private static final RowMapper<Consent> MAPPER = (rs, rowNum) -> new Consent(
            rs.getString("consent_id"),
            rs.getString("visitor_id"),
            rs.getString("category"),
            ConsentDecision.valueOf(rs.getString("decision")),
            rs.getInt("consent_version"),
            rs.getLong("effective_start_utc"),
            (Long) rs.getObject("effective_end_utc"),
            rs.getLong("created_at_utc"));

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Consent consent) {
        jdbc.update("INSERT INTO visitor_consent (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                consent.consentId(),
                consent.visitorId(),
                consent.category(),
                consent.decision().name(),
                consent.consentVersion(),
                consent.effectiveStartUtc(),
                consent.effectiveEndUtc(),
                consent.createdAtUtc());
    }

    /**
     * 行锁读取某访客某类别与新区间 [start, end) 可能重叠的全部区间。
     * 两区间相交条件：{@code a.start < b.end AND a.end > b.start}（NULL 端视为 +∞）。
     * 必须在事务内调用。
     */
    public List<Consent> lockOverlapping(String visitorId, String category,
                                         long startUtc, Long endUtc) {
        // endUtc 为 null（长期有效）时，条件退化为 existing.start 有限即可能重叠
        String sql = "SELECT " + COLUMNS + " FROM visitor_consent "
                + "WHERE visitor_id = ? AND category = ? "
                + "AND effective_start_utc < COALESCE(?, 9223372036854775807) "
                + "AND COALESCE(effective_end_utc, 9223372036854775807) > ? "
                + "ORDER BY consent_version, effective_start_utc FOR UPDATE";
        return jdbc.query(sql, MAPPER, visitorId, category, endUtc, startUtc);
    }

    /**
     * 行锁读取某访客某类别在指定时刻生效（含起点、不含终点）的全部区间，按版本升序。
     * 调用方在内存中选出最高版本决定。必须在事务内调用。
     */
    public List<Consent> lockEffectiveAt(String visitorId, String category, long atUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ? "
                        + "AND effective_start_utc <= ? "
                        + "AND (effective_end_utc IS NULL OR effective_end_utc > ?) "
                        + "ORDER BY consent_version FOR UPDATE",
                MAPPER, visitorId, category, atUtc, atUtc);
    }

    /** 查询某访客某类别的全部同意区间（无锁），按生效起点、版本排序，供区间查询接口使用。 */
    public List<Consent> findByVisitorAndCategory(String visitorId, String category) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ? "
                        + "ORDER BY effective_start_utc, consent_version",
                MAPPER, visitorId, category);
    }

    /** 无锁读取某访客某类别在指定时刻生效的区间并按版本升序，供只读裁决预览使用。 */
    public List<Consent> findEffectiveAt(String visitorId, String category, long atUtc) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ? "
                        + "AND effective_start_utc <= ? "
                        + "AND (effective_end_utc IS NULL OR effective_end_utc > ?) "
                        + "ORDER BY consent_version",
                MAPPER, visitorId, category, atUtc, atUtc);
    }

    /** 行锁读取单个同意区间；不存在返回 empty。必须在事务内调用。 */
    public Optional<Consent> lockById(String consentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent WHERE consent_id = ? FOR UPDATE",
                        MAPPER, consentId)
                .stream()
                .findFirst();
    }

    /** 按编号普通读取。 */
    public Optional<Consent> findById(String consentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent WHERE consent_id = ?",
                        MAPPER, consentId)
                .stream()
                .findFirst();
    }

    /** 将同意区间终点截断为指定时刻（撤回生效中的区间）。 */
    public int truncateEnd(String consentId, long newEndUtc) {
        return jdbc.update("UPDATE visitor_consent SET effective_end_utc = ? WHERE consent_id = ?",
                newEndUtc, consentId);
    }

    /** 删除尚未生效即被撤回（截断会违反 CHECK 约束）的区间。 */
    public int deleteById(String consentId) {
        return jdbc.update("DELETE FROM visitor_consent WHERE consent_id = ?", consentId);
    }

    /**
     * 持取 访客+类别 互斥量行锁：不存在则先创建（并发创建冲突时重试取锁），
     * 用于串行化同一维度的同意提交，保证重叠检查不会被并发绕过。必须在事务内调用。
     */
    public void lockMutex(String visitorId, String category) {
        for (int attempt = 0; attempt < 2; attempt++) {
            List<Boolean> locked = jdbc.query(
                    "SELECT TRUE FROM consent_mutex WHERE visitor_id = ? AND category = ? FOR UPDATE",
                    (rs, rowNum) -> Boolean.TRUE, visitorId, category);
            if (!locked.isEmpty()) {
                return;
            }
            try {
                jdbc.update("INSERT INTO consent_mutex (visitor_id, category) VALUES (?, ?)",
                        visitorId, category);
                // 插入成功后该事务持行锁，直接返回
                return;
            } catch (DuplicateKeyException concurrent) {
                // 并发事务刚创建：下一轮 SELECT ... FOR UPDATE 等待其提交后取锁
            }
        }
    }
}
