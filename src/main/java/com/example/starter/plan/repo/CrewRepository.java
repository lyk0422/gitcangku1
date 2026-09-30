package com.example.starter.plan.repo;

import com.example.starter.plan.model.CrewMember;
import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.CrewWindow;
import com.example.starter.plan.model.LineSection;
import com.example.starter.plan.model.PlanCrew;
import com.example.starter.plan.model.PlanCrewMember;
import com.example.starter.plan.model.QualificationStatus;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 乘务员、资格版本、区段登记与发布乘务快照的 JDBC 持久化。
 * 时刻以 UTC 毫秒存储；所有写方法须在调用方事务内执行。
 */
@Repository
public class CrewRepository {

    private static final RowMapper<CrewMember> MEMBER_MAPPER = (rs, n) -> new CrewMember(
            rs.getLong("id"), rs.getString("member_key"), rs.getString("name"));

    private static final RowMapper<CrewQualification> QUAL_MAPPER = (rs, n) -> {
        Long validTo = (Long) rs.getObject("valid_to_utc");
        Long revokedAt = (Long) rs.getObject("revoked_at");
        return new CrewQualification(
                rs.getLong("id"),
                rs.getLong("member_id"),
                rs.getString("line_id"),
                rs.getInt("grade_level"),
                Instant.ofEpochMilli(rs.getLong("valid_from_utc")),
                validTo == null ? null : Instant.ofEpochMilli(validTo),
                rs.getInt("version"),
                QualificationStatus.valueOf(rs.getString("status")),
                rs.getLong("created_at"),
                revokedAt);
    };

    private static final RowMapper<LineSection> SECTION_MAPPER = (rs, n) -> new LineSection(
            rs.getString("section_id"), rs.getString("line_id"), rs.getInt("required_grade"));

    private static final RowMapper<PlanCrewMember> PLAN_MEMBER_MAPPER = (rs, n) -> {
        Long validTo = (Long) rs.getObject("valid_to_utc");
        return new PlanCrewMember(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getInt("seq"),
                rs.getLong("member_id"),
                rs.getString("member_key"),
                rs.getString("member_name"),
                rs.getLong("qualification_id"),
                rs.getInt("qualification_version"),
                rs.getInt("grade_level"),
                Instant.ofEpochMilli(rs.getLong("valid_from_utc")),
                validTo == null ? null : Instant.ofEpochMilli(validTo));
    };

    private final JdbcTemplate jdbc;

    public CrewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记乘务员，返回自增主键；工号重复抛 DuplicateKeyException。
     */
    public long insertMember(String memberKey, String name, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_crew_member (member_key, name, created_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, memberKey);
            ps.setString(2, name);
            ps.setLong(3, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<CrewMember> findMemberByKey(String memberKey) {
        return jdbc.query("SELECT id, member_key, name FROM rail_crew_member WHERE member_key = ?",
                MEMBER_MAPPER, memberKey).stream().findFirst();
    }

    /**
     * 按主键查询乘务员。
     */
    public Optional<CrewMember> findMemberById(long memberId) {
        return jdbc.query("SELECT id, member_key, name FROM rail_crew_member WHERE id = ?",
                MEMBER_MAPPER, memberId).stream().findFirst();
    }

    /**
     * 按工号集合批量查询乘务员。
     */
    public List<CrewMember> findMembersByKeys(Collection<String> memberKeys) {
        if (memberKeys.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        memberKeys.forEach(k -> placeholders.add("?"));
        return jdbc.query("SELECT id, member_key, name FROM rail_crew_member"
                + " WHERE member_key IN (" + placeholders + ")", MEMBER_MAPPER, memberKeys.toArray());
    }

    public List<CrewMember> findAllMembers() {
        return jdbc.query("SELECT id, member_key, name FROM rail_crew_member ORDER BY member_key",
                MEMBER_MAPPER);
    }

    /**
     * 查询人员在某线路上的全部资格版本，按版本号升序。
     */
    public List<CrewQualification> findQualifications(long memberId, String lineId) {
        return jdbc.query("SELECT id, member_id, line_id, grade_level, valid_from_utc, valid_to_utc,"
                        + " version, status, created_at, revoked_at FROM rail_crew_qualification"
                        + " WHERE member_id = ? AND line_id = ? ORDER BY version",
                QUAL_MAPPER, memberId, lineId);
    }

    /**
     * 查询人员在全部线路上的资格版本。
     */
    public List<CrewQualification> findQualificationsAnyLine(long memberId) {
        return jdbc.query("SELECT id, member_id, line_id, grade_level, valid_from_utc, valid_to_utc,"
                        + " version, status, created_at, revoked_at FROM rail_crew_qualification"
                        + " WHERE member_id = ? ORDER BY line_id, version",
                QUAL_MAPPER, memberId);
    }

    /**
     * 查询人员在某线路上的 ACTIVE 资格版本（理论上至多一条）并加行级写锁，
     * 须在事务内调用，用于发布与撤销/换发之间的并发裁决。
     */
    public List<CrewQualification> findActiveQualificationsForUpdate(long memberId, String lineId) {
        return jdbc.query("SELECT id, member_id, line_id, grade_level, valid_from_utc, valid_to_utc,"
                        + " version, status, created_at, revoked_at FROM rail_crew_qualification"
                        + " WHERE member_id = ? AND line_id = ? AND status = 'ACTIVE' FOR UPDATE",
                QUAL_MAPPER, memberId, lineId);
    }

    /**
     * 按主键加行级写锁加载资格版本，须在事务内调用。
     */
    public Optional<CrewQualification> findQualificationForUpdate(long qualificationId) {
        return jdbc.query("SELECT id, member_id, line_id, grade_level, valid_from_utc, valid_to_utc,"
                        + " version, status, created_at, revoked_at FROM rail_crew_qualification"
                        + " WHERE id = ? FOR UPDATE",
                QUAL_MAPPER, qualificationId).stream().findFirst();
    }

    /**
     * 按人员、线路与版本号加行级写锁加载资格版本，须在事务内调用。
     */
    public Optional<CrewQualification> findQualificationForUpdate(long memberId, String lineId,
                                                                  int version) {
        return jdbc.query("SELECT id, member_id, line_id, grade_level, valid_from_utc, valid_to_utc,"
                        + " version, status, created_at, revoked_at FROM rail_crew_qualification"
                        + " WHERE member_id = ? AND line_id = ? AND version = ? FOR UPDATE",
                QUAL_MAPPER, memberId, lineId, version).stream().findFirst();
    }

    /**
     * 授予新版本，返回自增主键。(member_id, line_id, version) 唯一约束兜底并发双授同号。
     */
    public long insertQualification(long memberId, String lineId, int gradeLevel,
                                    Instant validFrom, Instant validTo, int version,
                                    long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_crew_qualification (member_id, line_id, grade_level,"
                            + " valid_from_utc, valid_to_utc, version, status, created_at, revoked_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, memberId);
            ps.setString(2, lineId);
            ps.setInt(3, gradeLevel);
            ps.setLong(4, validFrom.toEpochMilli());
            if (validTo == null) {
                ps.setObject(5, null);
            } else {
                ps.setLong(5, validTo.toEpochMilli());
            }
            ps.setInt(6, version);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 将某人员某线路的 ACTIVE 版本置为 SUPERSEDED（授予新版本时调用）。
     */
    public int markActiveSuperseded(long memberId, String lineId) {
        return jdbc.update("UPDATE rail_crew_qualification SET status = 'SUPERSEDED'"
                + " WHERE member_id = ? AND line_id = ? AND status = 'ACTIVE'", memberId, lineId);
    }

    /**
     * 撤销指定版本：仅 ACTIVE 可撤销，返回受影响行数（0 表示状态已非 ACTIVE）。
     */
    public int revokeQualification(long qualificationId, long nowMillis) {
        return jdbc.update("UPDATE rail_crew_qualification SET status = 'REVOKED', revoked_at = ?"
                + " WHERE id = ? AND status = 'ACTIVE'", nowMillis, qualificationId);
    }

    /**
     * 登记区段；区段重复登记抛 DuplicateKeyException。
     */
    public void insertSection(String sectionId, String lineId, int requiredGrade, long nowMillis) {
        jdbc.update("INSERT INTO rail_line_section (section_id, line_id, required_grade, created_at)"
                + " VALUES (?, ?, ?, ?)", sectionId, lineId, requiredGrade, nowMillis);
    }

    public Optional<LineSection> findSection(String sectionId) {
        return jdbc.query("SELECT section_id, line_id, required_grade FROM rail_line_section"
                + " WHERE section_id = ?", SECTION_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 写入计划乘务组快照头。
     */
    public void insertPlanCrew(PlanCrew crew) {
        jdbc.update("INSERT INTO rail_plan_crew (plan_id, line_id, required_grade, created_at)"
                + " VALUES (?, ?, ?, ?)", crew.planId(), crew.lineId(), crew.requiredGrade(),
                crew.createdAt());
    }

    /**
     * 批量写入计划乘务组逐人快照。
     */
    public void insertPlanCrewMembers(long planId, List<PlanCrewMember> members) {
        jdbc.batchUpdate(
                "INSERT INTO rail_plan_crew_member (plan_id, seq, member_id, member_key, member_name,"
                        + " qualification_id, qualification_version, grade_level, valid_from_utc, valid_to_utc)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                members, members.size(),
                (ps, m) -> {
                    ps.setLong(1, planId);
                    ps.setInt(2, m.seq());
                    ps.setLong(3, m.memberId());
                    ps.setString(4, m.memberKey());
                    ps.setString(5, m.memberName());
                    ps.setLong(6, m.qualificationId());
                    ps.setInt(7, m.qualificationVersion());
                    ps.setInt(8, m.gradeLevel());
                    ps.setLong(9, m.validFromUtc().toEpochMilli());
                    if (m.validToUtc() == null) {
                        ps.setObject(10, null);
                    } else {
                        ps.setLong(10, m.validToUtc().toEpochMilli());
                    }
                });
    }

    /**
     * 查询计划乘务组快照头。
     */
    public Optional<PlanCrew> findPlanCrew(long planId) {
        return jdbc.query("SELECT plan_id, line_id, required_grade, created_at FROM rail_plan_crew"
                        + " WHERE plan_id = ?",
                (rs, n) -> new PlanCrew(rs.getLong("plan_id"), rs.getString("line_id"),
                        rs.getInt("required_grade"), rs.getLong("created_at")),
                planId).stream().findFirst();
    }

    /**
     * 查询计划乘务组逐人快照，按序号升序。
     */
    public List<PlanCrewMember> findPlanCrewMembers(long planId) {
        return jdbc.query("SELECT id, plan_id, seq, member_id, member_key, member_name,"
                        + " qualification_id, qualification_version, grade_level, valid_from_utc, valid_to_utc"
                        + " FROM rail_plan_crew_member WHERE plan_id = ? ORDER BY seq",
                PLAN_MEMBER_MAPPER, planId);
    }

    /**
     * 查询一组乘务员当前被其他已发布计划占用的时间窗（区间为该计划全部占用的最小起点到最大终点）。
     * 仅取 PUBLISHED 计划：取消计划的窗口立即释放；窗口区间左闭右开，相邻端点可交接。
     *
     * @param memberIds      乘务员 id 集合
     * @param excludePlanIds 排除的计划 id（发布为自身；改签为新旧两个计划）
     */
    public List<CrewWindow> findCrewWindows(Collection<Long> memberIds,
                                            Collection<Long> excludePlanIds) {
        if (memberIds.isEmpty()) {
            return new ArrayList<>();
        }
        StringJoiner memberPlaceholders = new StringJoiner(", ");
        memberIds.forEach(id -> memberPlaceholders.add("?"));
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT cm.member_id, p.schedule_key, MIN(o.start_utc) AS start_utc,"
                        + " MAX(o.end_utc) AS end_utc"
                        + " FROM rail_plan_crew_member cm"
                        + " JOIN rail_day_plan p ON p.id = cm.plan_id"
                        + " JOIN rail_plan_occupancy o ON o.plan_id = p.id"
                        + " WHERE p.status = 'PUBLISHED'"
                        + " AND cm.member_id IN (").append(memberPlaceholders).append(')');
        args.addAll(memberIds);
        if (!excludePlanIds.isEmpty()) {
            StringJoiner excludePlaceholders = new StringJoiner(", ");
            excludePlanIds.forEach(id -> excludePlaceholders.add("?"));
            sql.append(" AND cm.plan_id NOT IN (").append(excludePlaceholders).append(')');
            args.addAll(excludePlanIds);
        }
        sql.append(" GROUP BY cm.member_id, p.schedule_key, cm.plan_id ORDER BY cm.member_id, start_utc");
        return jdbc.query(sql.toString(), (rs, n) -> new CrewWindow(
                rs.getLong("member_id"),
                rs.getString("schedule_key"),
                Instant.ofEpochMilli(rs.getLong("start_utc")),
                Instant.ofEpochMilli(rs.getLong("end_utc"))), args.toArray());
    }
}
