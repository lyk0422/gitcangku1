package com.example.starter.exposure.repo;

import com.example.starter.exposure.domain.Consent;
import com.example.starter.exposure.domain.ConsentDecision;
import com.example.starter.exposure.domain.ConsentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 访客同意数据访问。
 *
 * <p>每个 (访客, 类别) 在 {@code consent_scope} 中持有一行父锁：同意授予、撤回与预占同意裁决
 * 均先 {@code SELECT ... FOR UPDATE} 该父行，从而在同一裁决域上按事务提交顺序串行化。</p>
 *
 * <p>{@code visitor_consent} 以区间残片表示“同类别有效区间不得重叠”的时间轴：覆盖时把旧区间
 * 截断为左右残片或整体标记 SUPERSEDED。所有加锁/写方法必须在事务内调用。</p>
 */
@Repository
public class ConsentRepository {

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Consent> MAPPER = (rs, rowNum) -> new Consent(
            rs.getString("consent_id"),
            rs.getString("visitor_id"),
            rs.getString("category"),
            ConsentDecision.valueOf(rs.getString("decision")),
            rs.getLong("consent_version"),
            rs.getLong("effective_start_utc"),
            rs.getLong("effective_end_utc"),
            ConsentStatus.valueOf(rs.getString("status")),
            rs.getLong("created_at_utc"),
            (Long) rs.getObject("withdrawn_at_utc"));

    private static final String COLUMNS =
            "consent_id, visitor_id, category, decision, consent_version, "
                    + "effective_start_utc, effective_end_utc, status, created_at_utc, withdrawn_at_utc";

    /** 确保裁决域父行存在（不存在则以当前提交创建）。 */
    public void ensureScope(String visitorId, String category, long createdAtUtc) {
        jdbc.update("INSERT INTO consent_scope (visitor_id, category, created_at_utc) "
                        + "SELECT ?, ?, ? WHERE NOT EXISTS ("
                        + "SELECT 1 FROM consent_scope WHERE visitor_id = ? AND category = ?)",
                visitorId, category, createdAtUtc, visitorId, category);
    }

    /** 行锁裁决域父行；不存在返回 empty（调用方应先 ensureScope）。 */
    public boolean lockScope(String visitorId, String category) {
        List<Long> ids = jdbc.query(
                "SELECT created_at_utc FROM consent_scope "
                        + "WHERE visitor_id = ? AND category = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), visitorId, category);
        return !ids.isEmpty();
    }

    /** 行锁读取某 (访客, 类别) 全部 ACTIVE 区间（含未来生效区间），按起点升序。 */
    public List<Consent> lockActiveIntervals(String visitorId, String category) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ? AND status = 'ACTIVE' "
                        + "ORDER BY effective_start_utc FOR UPDATE",
                MAPPER, visitorId, category);
    }

    /**
     * 读取该 (访客, 类别) 历史上的最大同意版本；无任何记录返回 0。
     * 调用方须已锁裁决域父行（同域授予/撤回/预占均串行化），故无需行锁。
     */
    public long maxVersion(String visitorId, String category) {
        Long max = jdbc.query("SELECT MAX(consent_version) FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ?",
                rs -> rs.next() ? (Long) rs.getObject(1) : null, visitorId, category);
        return max == null ? 0L : max;
    }

    /**
     * 读取某 (访客, 类别) 可见的同意区间时间轴，按生效起点、版本升序。
     * SUPERSEDED 为区间覆盖时产生的内部记账行（已由残片替代），不对外展示；
     * ACTIVE 与 WITHDRAWN 均保留（撤回记录仍可查询）。
     */
    public List<Consent> findAll(String visitorId, String category) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE visitor_id = ? AND category = ? AND status <> 'SUPERSEDED' "
                        + "ORDER BY effective_start_utc, consent_version",
                MAPPER, visitorId, category);
    }

    /** 按编号读取同意记录。 */
    public Optional<Consent> findById(String consentId) {        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent WHERE consent_id = ?",
                        MAPPER, consentId)
                .stream()
                .findFirst();
    }

    /** 行锁读取同意记录。 */
    public Optional<Consent> lockById(String consentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM visitor_consent "
                        + "WHERE consent_id = ? FOR UPDATE",
                        MAPPER, consentId)
                .stream()
                .findFirst();
    }

    /** 插入一条同意记录（可能是新版本区间或截断产生的残片）。 */
    public void insert(Consent consent) {
        jdbc.update("INSERT INTO visitor_consent (consent_id, visitor_id, category, decision, "
                        + "consent_version, effective_start_utc, effective_end_utc, status, "
                        + "created_at_utc, withdrawn_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                consent.consentId(),
                consent.visitorId(),
                consent.category(),
                consent.decision().name(),
                consent.consentVersion(),
                consent.effectiveStartUtc(),
                consent.effectiveEndUtc(),
                consent.status().name(),
                consent.createdAtUtc(),
                consent.withdrawnAtUtc());
    }

    /** 修改区间起点（截断左残片用）。 */
    public void updateStart(String consentId, long newStartUtc) {
        int rows = jdbc.update("UPDATE visitor_consent SET effective_start_utc = ? "
                + "WHERE consent_id = ?", newStartUtc, consentId);
        if (rows != 1) {
            throw new IllegalStateException("consent row missing: " + consentId);
        }
    }

    /** 修改区间终点（截断右残片用）。 */
    public void updateEnd(String consentId, long newEndUtc) {
        int rows = jdbc.update("UPDATE visitor_consent SET effective_end_utc = ? "
                + "WHERE consent_id = ?", newEndUtc, consentId);
        if (rows != 1) {
            throw new IllegalStateException("consent row missing: " + consentId);
        }
    }

    /** 将整条同意记录标记为被覆盖（区间完全落入新版本区间内）。 */
    public void markSuperseded(String consentId) {
        int rows = jdbc.update("UPDATE visitor_consent SET status = 'SUPERSEDED' "
                + "WHERE consent_id = ? AND status = 'ACTIVE'", consentId);
        if (rows != 1) {
            throw new IllegalStateException("active consent row missing: " + consentId);
        }
    }

    /**
     * CAS 撤回：仅 ACTIVE 可撤回，记录撤回时刻。
     *
     * @return 是否撤回成功（已撤回/被覆盖时为 false）
     */
    public boolean compareAndSetWithdrawn(String consentId, long withdrawnAtUtc) {
        int rows = jdbc.update("UPDATE visitor_consent SET status = 'WITHDRAWN', withdrawn_at_utc = ? "
                        + "WHERE consent_id = ? AND status = 'ACTIVE'",
                withdrawnAtUtc, consentId);
        return rows == 1;
    }
}
