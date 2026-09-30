package com.example.starter.repository;

import com.example.starter.domain.Allocation;
import com.example.starter.domain.AllocationStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 靠泊申请数据访问。
 */
@Repository
public class AllocationRepository {

    private static final AllocationRowMapper ROW_MAPPER = new AllocationRowMapper();

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate JDBC 模板
     */
    public AllocationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入申请记录并回填自增主键。
     *
     * @param allocation 申请
     */
    public void insert(Allocation allocation) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO berth_allocation (request_id, vessel_name, draft_m, safety_margin_m, "
                            + "berth_id, start_time, end_time, status, tide_version_id, min_tide_height_m, "
                            + "static_depth_m, available_depth_m, required_depth_m, clearance_m, "
                            + "review_tide_version_id, review_min_tide_height_m, review_available_depth_m, "
                            + "review_clearance_m, reject_reason, review_count, decided_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
                    int i = 1;
                    ps.setString(i++, allocation.getRequestId());
                    ps.setString(i++, allocation.getVesselName());
                    ps.setBigDecimal(i++, allocation.getDraftM());
                    ps.setBigDecimal(i++, allocation.getSafetyMarginM());
                    ps.setLong(i++, allocation.getBerthId());
                    ps.setTimestamp(i++, Timestamp.valueOf(allocation.getStartTime()));
                    ps.setTimestamp(i++, Timestamp.valueOf(allocation.getEndTime()));
                    ps.setString(i++, allocation.getStatus().name());
                    setLongOrNull(ps, i++, allocation.getTideVersionId());
                    ps.setBigDecimal(i++, allocation.getMinTideHeightM());
                    ps.setBigDecimal(i++, allocation.getStaticDepthM());
                    ps.setBigDecimal(i++, allocation.getAvailableDepthM());
                    ps.setBigDecimal(i++, allocation.getRequiredDepthM());
                    ps.setBigDecimal(i++, allocation.getClearanceM());
                    setLongOrNull(ps, i++, allocation.getReviewTideVersionId());
                    ps.setBigDecimal(i++, allocation.getReviewMinTideHeightM());
                    ps.setBigDecimal(i++, allocation.getReviewAvailableDepthM());
                    ps.setBigDecimal(i++, allocation.getReviewClearanceM());
                    ps.setString(i++, allocation.getRejectReason());
                    ps.setInt(i++, allocation.getReviewCount() == null ? 0 : allocation.getReviewCount());
                    if (allocation.getDecidedAt() == null) {
                        ps.setNull(i, Types.TIMESTAMP);
                    } else {
                        ps.setTimestamp(i, Timestamp.valueOf(allocation.getDecidedAt()));
                    }
                    return ps;
                }, keyHolder);
        allocation.setId(keyHolder.getKeyAs(Long.class));
    }

    /**
     * 按幂等键查询。
     *
     * @param requestId 幂等键
     * @return 申请
     */
    public Optional<Allocation> findByRequestId(String requestId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(BASE_SELECT + " WHERE request_id = ?",
                    ROW_MAPPER, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * 查询已确认且尚未开始（起始时间晚于给定时刻）的全部申请，可按泊位过滤。
     *
     * @param berthId 泊位主键，为空表示全部泊位
     * @param now     当前业务时间
     * @return 待重新校核的已确认申请
     */
    public List<Allocation> findConfirmedUnstarted(Long berthId, LocalDateTime now) {
        if (berthId == null) {
            return jdbcTemplate.query(
                    BASE_SELECT + " WHERE status = 'CONFIRMED' AND start_time > ? ORDER BY id ASC",
                    ROW_MAPPER, Timestamp.valueOf(now));
        }
        return jdbcTemplate.query(
                BASE_SELECT + " WHERE status = 'CONFIRMED' AND start_time > ? AND berth_id = ? ORDER BY id ASC",
                ROW_MAPPER, Timestamp.valueOf(now), berthId);
    }

    /**
     * 判断同泊位是否存在与 [start, end) 重叠的已确认区间（端点相接不算重叠）。
     *
     * @param berthId          泊位主键
     * @param start            起始时间（含）
     * @param end              结束时间（不含）
     * @param excludeRequestId 排除的申请 requestId，可为空
     * @return true 表示存在冲突
     */
    public boolean existsConfirmedOverlap(long berthId, LocalDateTime start, LocalDateTime end,
                                          String excludeRequestId) {
        String sql = "SELECT COUNT(1) FROM berth_allocation WHERE berth_id = ? AND status = 'CONFIRMED' "
                + "AND start_time < ? AND end_time > ?";
        Integer count;
        if (excludeRequestId == null) {
            count = jdbcTemplate.queryForObject(sql, Integer.class,
                    berthId, Timestamp.valueOf(end), Timestamp.valueOf(start));
        } else {
            count = jdbcTemplate.queryForObject(sql + " AND request_id <> ?", Integer.class,
                    berthId, Timestamp.valueOf(end), Timestamp.valueOf(start), excludeRequestId);
        }
        return count != null && count > 0;
    }

    /**
     * 查询某泊位全部申请，按起始时间升序。
     *
     * @param berthId 泊位主键
     * @return 申请集合
     */
    public List<Allocation> findByBerthOrderByStart(long berthId) {
        return jdbcTemplate.query(BASE_SELECT + " WHERE berth_id = ? ORDER BY start_time ASC, id ASC",
                ROW_MAPPER, berthId);
    }

    /**
     * 潮高版本发布后回写重新校核结论（挂起或释放后重新确认）。
     *
     * @param allocation 已带主键的申请
     */
    public void updateRevision(Allocation allocation) {
        jdbcTemplate.update(
                "UPDATE berth_allocation SET status = ?, review_tide_version_id = ?, "
                        + "review_min_tide_height_m = ?, review_available_depth_m = ?, review_clearance_m = ?, "
                        + "reject_reason = ?, updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                allocation.getStatus().name(),
                allocation.getReviewTideVersionId(),
                allocation.getReviewMinTideHeightM(),
                allocation.getReviewAvailableDepthM(),
                allocation.getReviewClearanceM(),
                allocation.getRejectReason(),
                allocation.getId());
    }

    /**
     * 人工复核后回写状态、复核校核快照与复核次数。
     *
     * @param allocation 已带主键的申请
     */
    public void updateReview(Allocation allocation) {
        jdbcTemplate.update(
                "UPDATE berth_allocation SET status = ?, tide_version_id = ?, min_tide_height_m = ?, "
                        + "available_depth_m = ?, clearance_m = ?, review_tide_version_id = ?, "
                        + "review_min_tide_height_m = ?, review_available_depth_m = ?, review_clearance_m = ?, "
                        + "reject_reason = ?, review_count = ?, decided_at = ?, "
                        + "updated_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                allocation.getStatus().name(),
                allocation.getTideVersionId(),
                allocation.getMinTideHeightM(),
                allocation.getAvailableDepthM(),
                allocation.getClearanceM(),
                allocation.getReviewTideVersionId(),
                allocation.getReviewMinTideHeightM(),
                allocation.getReviewAvailableDepthM(),
                allocation.getReviewClearanceM(),
                allocation.getRejectReason(),
                allocation.getReviewCount(),
                Timestamp.valueOf(allocation.getDecidedAt()),
                allocation.getId());
    }

    private static void setLongOrNull(java.sql.PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    private static final String BASE_SELECT =
            "SELECT id, request_id, vessel_name, draft_m, safety_margin_m, berth_id, start_time, end_time, "
                    + "status, tide_version_id, min_tide_height_m, static_depth_m, available_depth_m, "
                    + "required_depth_m, clearance_m, review_tide_version_id, review_min_tide_height_m, "
                    + "review_available_depth_m, review_clearance_m, reject_reason, review_count, "
                    + "decided_at, created_at, updated_at FROM berth_allocation";

    /**
     * 申请行映射器。
     */
    static final class AllocationRowMapper implements RowMapper<Allocation> {

        @Override
        public Allocation mapRow(ResultSet rs, int rowNum) throws SQLException {
            Allocation a = new Allocation();
            a.setId(rs.getLong("id"));
            a.setRequestId(rs.getString("request_id"));
            a.setVesselName(rs.getString("vessel_name"));
            a.setDraftM(rs.getBigDecimal("draft_m"));
            a.setSafetyMarginM(rs.getBigDecimal("safety_margin_m"));
            a.setBerthId(rs.getLong("berth_id"));
            a.setStartTime(rs.getTimestamp("start_time").toLocalDateTime());
            a.setEndTime(rs.getTimestamp("end_time").toLocalDateTime());
            a.setStatus(AllocationStatus.valueOf(rs.getString("status")));
            long tideVersionId = rs.getLong("tide_version_id");
            a.setTideVersionId(rs.wasNull() ? null : tideVersionId);
            a.setMinTideHeightM(rs.getBigDecimal("min_tide_height_m"));
            a.setStaticDepthM(rs.getBigDecimal("static_depth_m"));
            a.setAvailableDepthM(rs.getBigDecimal("available_depth_m"));
            a.setRequiredDepthM(rs.getBigDecimal("required_depth_m"));
            a.setClearanceM(rs.getBigDecimal("clearance_m"));
            long reviewVersionId = rs.getLong("review_tide_version_id");
            a.setReviewTideVersionId(rs.wasNull() ? null : reviewVersionId);
            a.setReviewMinTideHeightM(rs.getBigDecimal("review_min_tide_height_m"));
            a.setReviewAvailableDepthM(rs.getBigDecimal("review_available_depth_m"));
            a.setReviewClearanceM(rs.getBigDecimal("review_clearance_m"));
            a.setRejectReason(rs.getString("reject_reason"));
            a.setReviewCount(rs.getInt("review_count"));
            Timestamp decidedAt = rs.getTimestamp("decided_at");
            a.setDecidedAt(decidedAt == null ? null : decidedAt.toLocalDateTime());
            Timestamp createdAt = rs.getTimestamp("created_at");
            a.setCreatedAt(createdAt == null ? null : createdAt.toLocalDateTime());
            Timestamp updatedAt = rs.getTimestamp("updated_at");
            a.setUpdatedAt(updatedAt == null ? null : updatedAt.toLocalDateTime());
            return a;
        }
    }
}
