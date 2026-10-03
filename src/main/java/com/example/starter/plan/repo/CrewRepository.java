package com.example.starter.plan.repo;

import com.example.starter.plan.model.CrewQualification;
import com.example.starter.plan.model.PublishedCrewAssignment;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 乘务资格、发布乘务快照与区段等级的 JDBC 持久化。所有时刻以 UTC 毫秒存储。
 */
@Repository
public class CrewRepository {

    private static final RowMapper<CrewQualification> QUAL_MAPPER = (rs, n) -> new CrewQualification(
            rs.getLong("id"),
            rs.getString("crew_member_id"),
            rs.getInt("line_grade"),
            Instant.ofEpochMilli(rs.getLong("valid_from_utc")),
            Instant.ofEpochMilli(rs.getLong("valid_to_utc")),
            rs.getInt("version"),
            rs.getString("status"));

    private static final RowMapper<PublishedCrewAssignment> ASSIGNMENT_MAPPER =
            (rs, n) -> new PublishedCrewAssignment(
                    rs.getLong("id"),
                    rs.getLong("plan_id"),
                    rs.getInt("seq"),
                    rs.getString("crew_member_id"),
                    rs.getLong("qualification_id"),
                    rs.getInt("qualification_version"),
                    rs.getInt("line_grade"),
                    Instant.ofEpochMilli(rs.getLong("valid_from_utc")),
                    Instant.ofEpochMilli(rs.getLong("valid_to_utc")),
                    Instant.ofEpochMilli(rs.getLong("trip_start_utc")),
                    Instant.ofEpochMilli(rs.getLong("trip_end_utc")));

    private static final String QUAL_COLUMNS =
            "id, crew_member_id, line_grade, valid_from_utc, valid_to_utc, version, status";

    private static final String ASSIGNMENT_COLUMNS =
            "id, plan_id, seq, crew_member_id, qualification_id, qualification_version, line_grade,"
                    + " valid_from_utc, valid_to_utc, trip_start_utc, trip_end_utc";

    private final JdbcTemplate jdbc;

    public CrewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按乘务员 ID 查询资格（不加锁）。
     */
    public Optional<CrewQualification> findQualification(String crewMemberId) {
        return jdbc.query("SELECT " + QUAL_COLUMNS + " FROM crew_qualification WHERE crew_member_id = ?",
                QUAL_MAPPER, crewMemberId).stream().findFirst();
    }

    /**
     * 按乘务员 ID 集合查询资格并加行级写锁（按 ID 升序，避免死锁），须在事务内调用。
     * 发布与撤销并发时据此串行化，保证发布不会基于已失效资格。
     */
    public List<CrewQualification> findQualificationsForUpdate(Collection<String> crewMemberIds) {
        if (crewMemberIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        crewMemberIds.forEach(id -> placeholders.add("?"));
        return jdbc.query("SELECT " + QUAL_COLUMNS + " FROM crew_qualification"
                        + " WHERE crew_member_id IN (" + placeholders + ")"
                        + " ORDER BY crew_member_id FOR UPDATE",
                QUAL_MAPPER, crewMemberIds.toArray());
    }

    /**
     * 授予新资格（版本 1，ACTIVE），返回自增主键。
     */
    public long insertQualification(String crewMemberId, int lineGrade,
                                    Instant validFromUtc, Instant validToUtc, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO crew_qualification (crew_member_id, line_grade,"
                            + " valid_from_utc, valid_to_utc, version, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, 1, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, crewMemberId);
            ps.setInt(2, lineGrade);
            ps.setLong(3, validFromUtc.toEpochMilli());
            ps.setLong(4, validToUtc.toEpochMilli());
            ps.setString(5, CrewQualification.STATUS_ACTIVE);
            ps.setLong(6, nowMillis);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 更新资格区间、等级、状态并递增版本（撤销或撤销后再授予）。
     */
    public void updateQualification(long qualificationId, int lineGrade,
                                    Instant validFromUtc, Instant validToUtc,
                                    int version, String status, long nowMillis) {
        jdbc.update("UPDATE crew_qualification SET line_grade = ?, valid_from_utc = ?,"
                        + " valid_to_utc = ?, version = ?, status = ?, updated_at = ? WHERE id = ?",
                lineGrade, validFromUtc.toEpochMilli(), validToUtc.toEpochMilli(),
                version, status, nowMillis, qualificationId);
    }

    /**
     * 批量写入发布乘务快照（不可变，仅在发布事务内追加）。
     */
    public void insertCrewAssignments(List<PublishedCrewAssignment> assignments) {
        jdbc.batchUpdate(
                "INSERT INTO published_crew_assignment (plan_id, seq, crew_member_id,"
                        + " qualification_id, qualification_version, line_grade,"
                        + " valid_from_utc, valid_to_utc, trip_start_utc, trip_end_utc, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                assignments, assignments.size(),
                (ps, a) -> {
                    ps.setLong(1, a.planId());
                    ps.setInt(2, a.seq());
                    ps.setString(3, a.crewMemberId());
                    ps.setLong(4, a.qualificationId());
                    ps.setInt(5, a.qualificationVersion());
                    ps.setInt(6, a.lineGrade());
                    ps.setLong(7, a.validFromUtc().toEpochMilli());
                    ps.setLong(8, a.validToUtc().toEpochMilli());
                    ps.setLong(9, a.tripStartUtc().toEpochMilli());
                    ps.setLong(10, a.tripEndUtc().toEpochMilli());
                    ps.setLong(11, System.currentTimeMillis());
                });
    }

    /**
     * 查询计划的发布乘务快照，按乘务组内序号升序。
     */
    public List<PublishedCrewAssignment> findCrewAssignments(long planId) {
        return jdbc.query("SELECT " + ASSIGNMENT_COLUMNS
                        + " FROM published_crew_assignment WHERE plan_id = ? ORDER BY seq",
                ASSIGNMENT_MAPPER, planId);
    }

    /**
     * 查询指定乘务员在其他已发布计划上的占用窗口（含冲突计划业务键），按成员与开始时刻升序。
     */
    public List<CrewOccupancy> findPublishedCrewOccupancies(Collection<String> crewMemberIds,
                                                            long excludePlanId) {
        if (crewMemberIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        crewMemberIds.forEach(id -> placeholders.add("?"));
        String sql = "SELECT a.crew_member_id, p.schedule_key, a.trip_start_utc, a.trip_end_utc"
                + " FROM published_crew_assignment a JOIN rail_day_plan p ON p.id = a.plan_id"
                + " WHERE p.status = 'PUBLISHED' AND p.id <> ?"
                + " AND a.crew_member_id IN (" + placeholders + ")"
                + " ORDER BY a.crew_member_id, a.trip_start_utc";
        List<Object> args = new java.util.ArrayList<>();
        args.add(excludePlanId);
        args.addAll(crewMemberIds);
        return jdbc.query(sql,
                (rs, n) -> new CrewOccupancy(
                        rs.getString("crew_member_id"),
                        rs.getString("schedule_key"),
                        Instant.ofEpochMilli(rs.getLong("trip_start_utc")),
                        Instant.ofEpochMilli(rs.getLong("trip_end_utc"))),
                args.toArray());
    }

    /**
     * 登记或更新区段线路等级（ upsert ）。
     */
    public void upsertSectionGrade(String sectionId, int lineGrade, long nowMillis) {
        int updated = jdbc.update("UPDATE rail_section_grade SET line_grade = ?, updated_at = ?"
                + " WHERE section_id = ?", lineGrade, nowMillis, sectionId);
        if (updated == 0) {
            jdbc.update("INSERT INTO rail_section_grade (section_id, line_grade, updated_at)"
                    + " VALUES (?, ?, ?)", sectionId, lineGrade, nowMillis);
        }
    }

    /**
     * 查询区段等级，未登记的区段不出现在结果中（调用方按 0 处理）。
     */
    public Map<String, Integer> findSectionGrades(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return Map.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(id -> placeholders.add("?"));
        Map<String, Integer> grades = new HashMap<>();
        jdbc.query("SELECT section_id, line_grade FROM rail_section_grade"
                        + " WHERE section_id IN (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> grades.put(rs.getString("section_id"), rs.getInt("line_grade")),
                sectionIds.toArray());
        return grades;
    }

    /**
     * 乘务员在已发布计划上的一段占用窗口。
     *
     * @param crewMemberId 乘务员 ID
     * @param scheduleKey  占用所在计划业务键
     * @param tripStartUtc 占用开始（含），UTC
     * @param tripEndUtc   占用结束（不含），UTC
     */
    public record CrewOccupancy(String crewMemberId, String scheduleKey,
                                Instant tripStartUtc, Instant tripEndUtc) {
    }
}