package com.example.starter.plan.repo;

import com.example.starter.plan.model.CrewAssignment;
import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.OccupiedCrewSlot;
import com.example.starter.plan.model.QualificationStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 乘务组、资格与发布乘务快照的 JDBC 持久化。所有时刻以 UTC 毫秒存储，时区无关。
 */
@Repository
public class CrewRepository {

    private static final RowMapper<CrewQualification> QUAL_MAPPER = (rs, n) -> new CrewQualification(
            rs.getLong("id"),
            rs.getString("member_code"),
            rs.getInt("version"),
            rs.getInt("line_grade"),
            Instant.ofEpochMilli(rs.getLong("valid_from_utc")),
            Instant.ofEpochMilli(rs.getLong("valid_to_utc")),
            QualificationStatus.valueOf(rs.getString("status")));

    private static final RowMapper<CrewAssignment> ASSIGNMENT_MAPPER = (rs, n) -> new CrewAssignment(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("group_code"),
            rs.getString("member_code"),
            rs.getInt("qual_version"),
            rs.getInt("qual_line_grade"),
            Instant.ofEpochMilli(rs.getLong("qual_valid_from_utc")),
            Instant.ofEpochMilli(rs.getLong("qual_valid_to_utc")),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private final JdbcTemplate jdbc;

    public CrewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建乘务组，返回自增主键；组键冲突时抛出 DuplicateKeyException 由上层裁决。
     */
    public long insertGroup(String groupCode, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO crew_group (group_code, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, groupCode);
            ps.setLong(2, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量写入乘务组成员名册，保持提交顺序。
     */
    public void insertGroupMembers(String groupCode, List<String> memberCodes) {
        List<Object[]> rows = new java.util.ArrayList<>(memberCodes.size());
        for (int i = 0; i < memberCodes.size(); i++) {
            rows.add(new Object[] {groupCode, memberCodes.get(i), i});
        }
        jdbc.batchUpdate(
                "INSERT INTO crew_group_member (group_code, member_code, seq) VALUES (?, ?, ?)",
                rows);
    }

    /**
     * 判断乘务组是否存在。
     */
    public boolean groupExists(String groupCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM crew_group WHERE group_code = ?", Integer.class, groupCode);
        return count != null && count > 0;
    }

    /**
     * 查询乘务组成员，按成员业务键升序（查询展示的稳定排序）。
     */
    public List<String> findGroupMembers(String groupCode) {
        return jdbc.query("SELECT member_code FROM crew_group_member WHERE group_code = ?"
                        + " ORDER BY member_code",
                (rs, n) -> rs.getString("member_code"), groupCode);
    }

    /**
     * 查询乘务员当前有效（ACTIVE）资格；授予与撤销经发布锁串行化，事务内读取即一致。
     */
    public Optional<CrewQualification> findActiveQualification(String memberCode) {
        return jdbc.query("SELECT id, member_code, version, line_grade, valid_from_utc,"
                        + " valid_to_utc, status FROM crew_qualification"
                        + " WHERE member_code = ? AND status = 'ACTIVE'"
                        + " ORDER BY version DESC",
                QUAL_MAPPER, memberCode).stream().findFirst();
    }

    /**
     * 查询乘务员当前最大资格版本，无资格时返回 0。
     */
    public int findMaxQualificationVersion(String memberCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM crew_qualification WHERE member_code = ?",
                Integer.class, memberCode);
        return max == null ? 0 : max;
    }

    /**
     * 授予新版本资格：旧 ACTIVE 版本置为 SUPERSEDED，插入新 ACTIVE 版本。
     */
    public void grantQualification(String memberCode, int version, int lineGrade,
                                   Instant validFromUtc, Instant validToUtc, long nowMillis) {
        jdbc.update("UPDATE crew_qualification SET status = 'SUPERSEDED', updated_at = ?"
                        + " WHERE member_code = ? AND status = 'ACTIVE'",
                nowMillis, memberCode);
        jdbc.update("INSERT INTO crew_qualification (member_code, version, line_grade,"
                        + " valid_from_utc, valid_to_utc, status, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                memberCode, version, lineGrade, validFromUtc.toEpochMilli(),
                validToUtc.toEpochMilli(), nowMillis, nowMillis);
    }

    /**
     * 撤销乘务员当前有效资格，返回被撤销的资格；无有效资格时返回空。
     */
    public Optional<CrewQualification> revokeActiveQualification(String memberCode, long nowMillis) {
        Optional<CrewQualification> active = findActiveQualification(memberCode);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("UPDATE crew_qualification SET status = 'REVOKED', updated_at = ?"
                        + " WHERE member_code = ? AND status = 'ACTIVE'", nowMillis, memberCode);
        CrewQualification q = active.get();
        return Optional.of(new CrewQualification(q.id(), q.memberCode(), q.version(), q.lineGrade(),
                q.validFromUtc(), q.validToUtc(), QualificationStatus.REVOKED));
    }

    /**
     * 查询给定区段集合中的最高要求等级，未登记的区段按 0（无要求）处理。
     */
    public int findMaxRequiredGrade(Collection<String> sectionIds) {
        int max = 0;
        for (String sectionId : sectionIds) {
            Integer grade = jdbc.query(
                    "SELECT required_grade FROM rail_section_grade WHERE section_id = ?",
                    (rs, n) -> rs.getInt("required_grade"), sectionId).stream().findFirst().orElse(0);
            max = Math.max(max, grade);
        }
        return max;
    }

    /**
     * 写入发布乘务占用快照（不可变）。
     */
    public void insertAssignments(List<CrewAssignment> assignments, long nowMillis) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_crew_assignment (plan_id, group_code, member_code,"
                        + " qual_version, qual_line_grade, qual_valid_from_utc, qual_valid_to_utc,"
                        + " start_utc, end_utc, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                assignments, assignments.size(),
                (ps, a) -> {
                    ps.setLong(1, a.planId());
                    ps.setString(2, a.groupCode());
                    ps.setString(3, a.memberCode());
                    ps.setInt(4, a.qualVersion());
                    ps.setInt(5, a.qualLineGrade());
                    ps.setLong(6, a.qualValidFromUtc().toEpochMilli());
                    ps.setLong(7, a.qualValidToUtc().toEpochMilli());
                    ps.setLong(8, a.startUtc().toEpochMilli());
                    ps.setLong(9, a.endUtc().toEpochMilli());
                    ps.setLong(10, nowMillis);
                });
    }

    /**
     * 查询计划的发布乘务快照，按成员业务键升序。
     */
    public List<CrewAssignment> findAssignments(long planId) {
        return jdbc.query("SELECT id, plan_id, group_code, member_code, qual_version,"
                        + " qual_line_grade, qual_valid_from_utc, qual_valid_to_utc,"
                        + " start_utc, end_utc FROM rail_plan_crew_assignment"
                        + " WHERE plan_id = ? ORDER BY member_code",
                ASSIGNMENT_MAPPER, planId);
    }

    /**
     * 查询指定乘务员在其他已发布计划上与给定窗口重叠（左闭右开，相邻不算）的生效占用。
     */
    public List<OccupiedCrewSlot> findConflictingCrewSlots(String memberCode, Instant startUtc,
                                                           Instant endUtc, long excludePlanId) {
        return jdbc.query("SELECT p.schedule_key, a.member_code, a.start_utc, a.end_utc"
                        + " FROM rail_plan_crew_assignment a JOIN rail_day_plan p ON p.id = a.plan_id"
                        + " WHERE p.status = 'PUBLISHED' AND a.member_code = ?"
                        + " AND a.plan_id <> ? AND a.start_utc < ? AND ? < a.end_utc"
                        + " ORDER BY p.schedule_key",
                (rs, n) -> new OccupiedCrewSlot(
                        rs.getString("schedule_key"),
                        rs.getString("member_code"),
                        Instant.ofEpochMilli(rs.getLong("start_utc")),
                        Instant.ofEpochMilli(rs.getLong("end_utc"))),
                memberCode, excludePlanId, endUtc.toEpochMilli(), startUtc.toEpochMilli());
    }
}