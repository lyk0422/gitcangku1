package com.example.starter.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.po.ApplicationPo;

/**
 * 靠泊申请仓储；仅 CONFIRMED 状态记录占用泊位。
 */
@Repository
public class ApplicationRepository {

    private static final RowMapper<ApplicationPo> MAPPER = (rs, n) -> new ApplicationPo(
            rs.getObject("id", Long.class),
            rs.getString("request_id"),
            rs.getString("vessel_name"),
            rs.getBigDecimal("draft"),
            rs.getBigDecimal("safety_margin"),
            rs.getString("berth_code"),
            rs.getTimestamp("start_time").toLocalDateTime(),
            rs.getTimestamp("end_time").toLocalDateTime(),
            rs.getString("status"),
            rs.getString("rejection_code"),
            rs.getString("rejection_reason"),
            rs.getBigDecimal("static_depth"),
            rs.getObject("tide_version_id", Long.class),
            rs.getObject("tide_version_no", Long.class),
            rs.getBigDecimal("min_tide_height"),
            rs.getBigDecimal("available_depth"),
            rs.getBigDecimal("clearance"),
            rs.getObject("snap_tide_version_no", Long.class),
            rs.getBigDecimal("snap_min_tide_height"),
            rs.getBigDecimal("snap_available_depth"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime(),
            rs.getTimestamp("confirmed_at") == null ? null : rs.getTimestamp("confirmed_at").toLocalDateTime(),
            rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ApplicationPo findByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(baseSql() + " WHERE request_id = ?", MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public List<ApplicationPo> findByBerth(String berthCode) {
        return jdbcTemplate.query(baseSql() + " WHERE berth_code = ? ORDER BY start_time, id",
                MAPPER, berthCode);
    }

    /**
     * 查询尚未开始（start_time 严格晚于给定时刻）的已确认靠泊，潮高修订时联动使用。
     */
    public List<ApplicationPo> findConfirmedStartingAfter(LocalDateTime time) {
        return jdbcTemplate.query(
                baseSql() + " WHERE status = 'CONFIRMED' AND start_time > ? ORDER BY id",
                MAPPER, Timestamp.valueOf(time));
    }

    /**
     * 行级锁定申请记录，供复核事务串行使用。
     */
    public ApplicationPo lockByRequestId(String requestId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT * FROM berth_application WHERE request_id = ? FOR UPDATE",
                    MAPPER, requestId);
        } catch (EmptyResultDataAccessException ex) {
            return null;
        }
    }

    public void insert(ApplicationPo po) {
        jdbcTemplate.update(
                "INSERT INTO berth_application (request_id, vessel_name, draft, safety_margin, "
                        + "berth_code, start_time, end_time, status, rejection_code, rejection_reason, "
                        + "static_depth, tide_version_id, tide_version_no, min_tide_height, "
                        + "available_depth, clearance, snap_tide_version_no, snap_min_tide_height, "
                        + "snap_available_depth, request_hash, created_at, updated_at, confirmed_at, reviewed_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                po.requestId(), po.vesselName(), po.draft(), po.safetyMargin(),
                po.berthCode(), po.startTime(), po.endTime(), po.status(),
                po.rejectionCode(), po.rejectionReason(), po.staticDepth(),
                po.tideVersionId(), po.tideVersionNo(), po.minTideHeight(),
                po.availableDepth(), po.clearance(), po.snapTideVersionNo(),
                po.snapMinTideHeight(), po.snapAvailableDepth(),
                po.requestHash(), po.createdAt(), po.updatedAt(),
                po.confirmedAt() == null ? null : Timestamp.valueOf(po.confirmedAt()),
                po.reviewedAt() == null ? null : Timestamp.valueOf(po.reviewedAt()));
    }

    public void update(ApplicationPo po) {
        jdbcTemplate.update(
                "UPDATE berth_application SET vessel_name = ?, draft = ?, safety_margin = ?, "
                        + "berth_code = ?, start_time = ?, end_time = ?, status = ?, "
                        + "rejection_code = ?, rejection_reason = ?, static_depth = ?, "
                        + "tide_version_id = ?, tide_version_no = ?, min_tide_height = ?, "
                        + "available_depth = ?, clearance = ?, snap_tide_version_no = ?, "
                        + "snap_min_tide_height = ?, snap_available_depth = ?, updated_at = ?, "
                        + "confirmed_at = ?, reviewed_at = ? WHERE request_id = ?",
                po.vesselName(), po.draft(), po.safetyMargin(), po.berthCode(),
                po.startTime(), po.endTime(), po.status(), po.rejectionCode(), po.rejectionReason(),
                po.staticDepth(), po.tideVersionId(), po.tideVersionNo(), po.minTideHeight(),
                po.availableDepth(), po.clearance(), po.snapTideVersionNo(),
                po.snapMinTideHeight(), po.snapAvailableDepth(), po.updatedAt(),
                po.confirmedAt() == null ? null : Timestamp.valueOf(po.confirmedAt()),
                po.reviewedAt() == null ? null : Timestamp.valueOf(po.reviewedAt()),
                po.requestId());
    }

    /**
     * 统计同一泊位上与 [start,end) 左闭右开区间重叠的已确认靠泊数量；排除指定申请（自身重算场景）。
     */
    public int countConfirmedOverlap(String berthCode, LocalDateTime start, LocalDateTime end,
                                     String excludeRequestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM berth_application WHERE berth_code = ? AND status = 'CONFIRMED' "
                        + "AND start_time < ? AND end_time > ? AND request_id <> ?",
                Integer.class, berthCode, Timestamp.valueOf(end), Timestamp.valueOf(start),
                excludeRequestId == null ? "" : excludeRequestId);
        return count == null ? 0 : count;
    }

    private String baseSql() {
        return "SELECT id, request_id, vessel_name, draft, safety_margin, berth_code, start_time, "
                + "end_time, status, rejection_code, rejection_reason, static_depth, tide_version_id, "
                + "tide_version_no, min_tide_height, available_depth, clearance, snap_tide_version_no, "
                + "snap_min_tide_height, snap_available_depth, request_hash, created_at, updated_at, "
                + "confirmed_at, reviewed_at FROM berth_application";
    }
}
