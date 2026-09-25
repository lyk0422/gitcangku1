package com.example.starter.plan.repo;

import com.example.starter.plan.model.PreemptionRecord;
import com.example.starter.plan.model.PreemptionSection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 不可变抢占记录持久化：固化抢占双方计划、涉及区段与各自等级。
 * 记录只增不改，用于"同一时隙只能被抢占一次"的判定与抢占链查询。
 */
@Repository
public class PreemptionRepository {

    private static final RowMapper<PreemptionRecord> RECORD_MAPPER = (rs, n) -> new PreemptionRecord(
            rs.getLong("id"),
            rs.getObject("op_date", LocalDate.class),
            rs.getString("preempting_schedule_key"),
            rs.getString("preempted_schedule_key"),
            rs.getInt("preempting_level"),
            rs.getInt("preempted_level"),
            rs.getString("preempt_key"),
            rs.getLong("created_at"));

    private static final RowMapper<PreemptionSection> SECTION_MAPPER = (rs, n) -> new PreemptionSection(
            rs.getLong("id"),
            rs.getLong("record_id"),
            rs.getString("section_id"),
            rs.getInt("section_level"),
            Instant.ofEpochMilli(rs.getLong("start_utc")),
            Instant.ofEpochMilli(rs.getLong("end_utc")));

    private final JdbcTemplate jdbc;

    public PreemptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入抢占记录主行，返回自增主键。
     */
    public long insertRecord(LocalDate opDate, String preemptingKey, String preemptedKey,
                             int preemptingLevel, int preemptedLevel, String preemptKey,
                             long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_preemption_record (op_date, preempting_schedule_key,"
                            + " preempted_schedule_key, preempting_level, preempted_level,"
                            + " preempt_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setDate(1, Date.valueOf(opDate));
            ps.setString(2, preemptingKey);
            ps.setString(3, preemptedKey);
            ps.setInt(4, preemptingLevel);
            ps.setInt(5, preemptedLevel);
            ps.setString(6, preemptKey);
            ps.setLong(7, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 批量写入抢占记录涉及的区段与时隙明细。
     */
    public void insertSections(long recordId, List<PreemptionSection> sections) {
        jdbc.batchUpdate(
                "INSERT INTO rail_preemption_record_section (record_id, section_id, section_level,"
                        + " start_utc, end_utc) VALUES (?, ?, ?, ?, ?)",
                sections, sections.size(),
                (ps, s) -> {
                    ps.setLong(1, recordId);
                    ps.setString(2, s.sectionId());
                    ps.setInt(3, s.sectionLevel());
                    ps.setLong(4, s.startUtc().toEpochMilli());
                    ps.setLong(5, s.endUtc().toEpochMilli());
                });
    }

    /**
     * 判定同一运营日、同一区段上与给定区间（左闭右开）重叠的时隙是否已被抢占过。
     */
    public boolean existsOverlapping(LocalDate opDate, String sectionId, Instant startUtc,
                                     Instant endUtc) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rail_preemption_record_section s"
                        + " JOIN rail_preemption_record r ON r.id = s.record_id"
                        + " WHERE r.op_date = ? AND s.section_id = ?"
                        + " AND s.start_utc < ? AND ? < s.end_utc",
                Integer.class, Date.valueOf(opDate), sectionId,
                endUtc.toEpochMilli(), startUtc.toEpochMilli());
        return count != null && count > 0;
    }

    /**
     * 按可选过滤条件查询抢占记录（opDate、sectionId 均可为 null 表示不过滤），按 id 升序。
     */
    public List<PreemptionRecord> findRecords(LocalDate opDate, String sectionId) {
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT r.id, r.op_date, r.preempting_schedule_key,"
                        + " r.preempted_schedule_key, r.preempting_level, r.preempted_level,"
                        + " r.preempt_key, r.created_at FROM rail_preemption_record r");
        List<Object> args = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        if (sectionId != null) {
            sql.append(" JOIN rail_preemption_record_section s ON s.record_id = r.id");
            conditions.add("s.section_id = ?");
            args.add(sectionId);
        }
        if (opDate != null) {
            conditions.add("r.op_date = ?");
            args.add(Date.valueOf(opDate));
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY r.id");
        return jdbc.query(sql.toString(), RECORD_MAPPER, args.toArray());
    }

    /**
     * 查询指定抢占记录集合的区段明细，按记录 id 与区段 id 排序。
     */
    public List<PreemptionSection> findSections(List<Long> recordIds) {
        if (recordIds.isEmpty()) {
            return List.of();
        }
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < recordIds.size(); i++) {
            placeholders.append(i == 0 ? "?" : ", ?");
        }
        return jdbc.query("SELECT id, record_id, section_id, section_level, start_utc, end_utc"
                        + " FROM rail_preemption_record_section WHERE record_id IN ("
                        + placeholders + ") ORDER BY record_id, section_id",
                SECTION_MAPPER, recordIds.toArray());
    }
}
