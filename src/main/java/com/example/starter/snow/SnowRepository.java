package com.example.starter.snow;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 雪况与解除审核的 JDBC 数据访问；所有时间以 UTC Instant 存取。
 */
@Repository
public class SnowRepository {

    private final JdbcTemplate jdbc;

    public SnowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读数窗口行；effectiveNewSnowCm 为最近一次勘误后的有效新增积雪，无勘误时等于原值。 */
    record WindowRow(Instant hourUtc, int snowDepthCm, int newSnowCm, int effectiveNewSnowCm) {
    }

    /** 封闭命令行；closedAt 为空表示仍封闭。 */
    record ClosureRow(long id, String reason, String status, Instant createdAt, Instant closedAt) {
    }

    /** 审核单行。 */
    record ReviewRow(long id, String areaId, String status, Instant windowStart, Instant windowEnd,
            long readingsVersion, String invalidatedReason, Instant createdAt, Instant completedAt) {
    }

    /** 解除决定行。 */
    record DecisionRow(long id, long reviewId, long version, Instant effectiveAt) {
    }

    /** 幂等记录行。 */
    record IdemRow(String payloadHash, int status, String body) {
    }

    private static Instant toInstant(Object ts) {
        return ts == null ? null : ((Timestamp) ts).toInstant();
    }

    /** 区域不存在时插入，存在则忽略。 */
    public void ensureArea(String areaId, String name, Instant now) {
        int updated = jdbc.update("INSERT INTO area (id, name, created_at) "
                + "SELECT ?, ?, ? FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM area WHERE id = ?)",
                areaId, name, Timestamp.from(now), areaId);
        if (updated == 0) {
            return;
        }
    }

    /** 对区域行加 FOR UPDATE 行锁，串行化该区域的所有写操作；返回区域是否存在。 */
    public boolean lockArea(String areaId) {
        List<String> ids = jdbc.query("SELECT id FROM area WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), areaId);
        return !ids.isEmpty();
    }

    /** 新增读数，返回自增ID。 */
    public long insertReading(String areaId, Instant hourUtc, int depthCm, int newSnowCm,
            String source, String requestId, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO snow_reading (area_id, hour_utc, snow_depth_cm, new_snow_cm, source, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, areaId);
            ps.setTimestamp(2, Timestamp.from(hourUtc));
            ps.setInt(3, depthCm);
            ps.setInt(4, newSnowCm);
            ps.setString(5, source);
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按区域与整点查询读数ID。 */
    public Optional<Long> findReadingId(String areaId, Instant hourUtc) {
        List<Long> ids = jdbc.query("SELECT id FROM snow_reading WHERE area_id = ? AND hour_utc = ?",
                (rs, n) -> rs.getLong(1), areaId, Timestamp.from(hourUtc));
        return ids.stream().findFirst();
    }

    /** 查询指定整点集合的窗口读数，附带最近一次勘误后的有效新增积雪。 */
    public List<WindowRow> findWindowRows(String areaId, List<Instant> hours) {
        String placeholders = String.join(", ", hours.stream().map(h -> "?").toList());
        Object[] args = new Object[hours.size() + 1];
        args[0] = areaId;
        for (int i = 0; i < hours.size(); i++) {
            args[i + 1] = Timestamp.from(hours.get(i));
        }
        RowMapper<WindowRow> mapper = (rs, n) -> {
            int original = rs.getInt("new_snow_cm");
            Integer corrected = (Integer) rs.getObject("corrected_new_snow_cm");
            return new WindowRow(rs.getTimestamp("hour_utc").toInstant(),
                    rs.getInt("snow_depth_cm"), original, corrected == null ? original : corrected);
        };
        return jdbc.query("SELECT r.hour_utc, r.snow_depth_cm, r.new_snow_cm, "
                + "(SELECT c.corrected_new_snow_cm FROM reading_correction c "
                + " WHERE c.reading_id = r.id AND c.corrected_new_snow_cm IS NOT NULL "
                + " ORDER BY c.id DESC LIMIT 1) AS corrected_new_snow_cm "
                + "FROM snow_reading r WHERE r.area_id = ? AND r.hour_utc IN (" + placeholders + ") "
                + "ORDER BY r.hour_utc", mapper, args);
    }

    /** 区域读数总数，作为读数版本号。 */
    public long countReadings(String areaId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM snow_reading WHERE area_id = ?",
                Long.class, areaId);
        return count == null ? 0 : count;
    }

    /** 追加勘误记录，返回自增ID。 */
    public long insertCorrection(long readingId, Integer depthCm, Integer newSnowCm,
            String reviewer, String note, String requestId, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO reading_correction (reading_id, corrected_snow_depth_cm, corrected_new_snow_cm, "
                            + "reviewer, note, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, readingId);
            if (depthCm == null) {
                ps.setNull(2, java.sql.Types.INTEGER);
            } else {
                ps.setInt(2, depthCm);
            }
            if (newSnowCm == null) {
                ps.setNull(3, java.sql.Types.INTEGER);
            } else {
                ps.setInt(3, newSnowCm);
            }
            ps.setString(4, reviewer);
            ps.setString(5, note);
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }
    private static final RowMapper<ClosureRow> CLOSURE_MAPPER = (rs, n) -> new ClosureRow(
            rs.getLong("id"), rs.getString("reason"), rs.getString("status"),
            rs.getTimestamp("created_at").toInstant(), toInstant(rs.getTimestamp("closed_at")));

    /** 查询区域当前生效中的封闭命令。 */
    public Optional<ClosureRow> findOpenClosure(String areaId) {
        return jdbc.query("SELECT * FROM closure_command WHERE area_id = ? AND status = 'OPEN' "
                + "ORDER BY id DESC LIMIT 1", CLOSURE_MAPPER, areaId).stream().findFirst();
    }

    /** 创建封闭命令，返回自增ID。 */
    public long insertClosure(String areaId, String reason, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO closure_command (area_id, reason, status, created_at) VALUES (?, ?, 'OPEN', ?)",
                    new String[] {"id"});
            ps.setString(1, areaId);
            ps.setString(2, reason);
            ps.setTimestamp(3, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 将区域生效中的封闭命令置为已解除，返回更新条数。 */
    public int closeOpenClosures(String areaId, Instant now) {
        return jdbc.update("UPDATE closure_command SET status = 'CLOSED', closed_at = ? "
                + "WHERE area_id = ? AND status = 'OPEN'", Timestamp.from(now), areaId);
    }

    /** 查询区域全部封闭命令（时间线用）。 */
    public List<ClosureRow> listClosures(String areaId) {
        return jdbc.query("SELECT * FROM closure_command WHERE area_id = ? ORDER BY id", CLOSURE_MAPPER, areaId);
    }

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> new ReviewRow(
            rs.getLong("id"), rs.getString("area_id"), rs.getString("status"),
            rs.getTimestamp("window_start").toInstant(), rs.getTimestamp("window_end").toInstant(),
            rs.getLong("readings_version"), rs.getString("invalidated_reason"),
            rs.getTimestamp("created_at").toInstant(), toInstant(rs.getTimestamp("completed_at")));

    /** 创建审核单（PENDING），返回自增ID。 */
    public long insertReview(String areaId, Instant windowStart, Instant windowEnd, long version, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO lift_review (area_id, status, window_start, window_end, readings_version, created_at) "
                            + "VALUES (?, 'PENDING', ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, areaId);
            ps.setTimestamp(2, Timestamp.from(windowStart));
            ps.setTimestamp(3, Timestamp.from(windowEnd));
            ps.setLong(4, version);
            ps.setTimestamp(5, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 按ID查询审核单。 */
    public Optional<ReviewRow> findReview(long reviewId) {
        return jdbc.query("SELECT * FROM lift_review WHERE id = ?", REVIEW_MAPPER, reviewId)
                .stream().findFirst();
    }

    /** 查询区域全部审核单。 */
    public List<ReviewRow> listReviews(String areaId) {
        return jdbc.query("SELECT * FROM lift_review WHERE area_id = ? ORDER BY id", REVIEW_MAPPER, areaId);
    }

    /** 将区域所有 PENDING 审核单置为失效，返回失效条数。 */
    public int invalidatePendingReviews(String areaId, String reason, Instant now) {
        return jdbc.update("UPDATE lift_review SET status = 'INVALIDATED', invalidated_reason = ?, completed_at = ? "
                + "WHERE area_id = ? AND status = 'PENDING'", reason, Timestamp.from(now), areaId);
    }

    /** 更新审核单状态。 */
    public void updateReviewStatus(long reviewId, String status, String invalidatedReason, Instant completedAt) {
        jdbc.update("UPDATE lift_review SET status = ?, invalidated_reason = ?, completed_at = ? WHERE id = ?",
                status, invalidatedReason, completedAt == null ? null : Timestamp.from(completedAt), reviewId);
    }

    /** 新增确认签字。 */
    public void insertConfirmation(long reviewId, String reviewer, String requestId, Instant now) {
        jdbc.update("INSERT INTO lift_confirmation (review_id, reviewer, request_id, confirmed_at) VALUES (?, ?, ?, ?)",
                reviewId, reviewer, requestId, Timestamp.from(now));
    }

    /** 查询审核单的确认人列表（按确认顺序）。 */
    public List<String> listReviewers(long reviewId) {
        return jdbc.query("SELECT reviewer FROM lift_confirmation WHERE review_id = ? ORDER BY id",
                (rs, n) -> rs.getString(1), reviewId);
    }

    /** 区域内下一个解除版本号。 */
    public long nextLiftVersion(String areaId) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM lift_decision WHERE area_id = ?",
                Long.class, areaId);
        return (max == null ? 0 : max) + 1;
    }

    /** 生成解除决定版本。 */
    public void insertDecision(String areaId, long reviewId, long version, Instant now) {
        jdbc.update("INSERT INTO lift_decision (area_id, review_id, version, effective_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", areaId, reviewId, version,
                Timestamp.from(now), Timestamp.from(now));
    }

    /** 按审核单查询解除决定。 */
    public Optional<DecisionRow> findDecisionByReview(long reviewId) {
        return jdbc.query("SELECT * FROM lift_decision WHERE review_id = ?",
                (rs, n) -> new DecisionRow(rs.getLong("id"), rs.getLong("review_id"), rs.getLong("version"),
                        rs.getTimestamp("effective_at").toInstant()),
                reviewId).stream().findFirst();
    }

    /** 查询区域全部解除决定（时间线用）。 */
    public List<DecisionRow> listDecisions(String areaId) {
        return jdbc.query("SELECT * FROM lift_decision WHERE area_id = ? ORDER BY version",
                (rs, n) -> new DecisionRow(rs.getLong("id"), rs.getLong("review_id"), rs.getLong("version"),
                        rs.getTimestamp("effective_at").toInstant()),
                areaId);
    }

    /** 查询幂等记录。 */
    public Optional<IdemRow> findIdempotency(String scope, String requestId) {
        return jdbc.query("SELECT payload_hash, response_status, response_body FROM idempotency_record "
                        + "WHERE scope = ? AND request_id = ?",
                (rs, n) -> new IdemRow(rs.getString(1), rs.getInt(2), rs.getString(3)),
                scope, requestId).stream().findFirst();
    }

    /** 写入幂等记录。 */
    public void insertIdempotency(String scope, String requestId, String hash, int status, String body, Instant now) {
        jdbc.update("INSERT INTO idempotency_record (scope, request_id, payload_hash, response_status, response_body, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?)", scope, requestId, hash, status, body, Timestamp.from(now));
    }
}