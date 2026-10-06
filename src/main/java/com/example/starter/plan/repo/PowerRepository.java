package com.example.starter.plan.repo;

import com.example.starter.plan.model.PowerAudit;
import com.example.starter.plan.model.SectionBinding;
import com.example.starter.plan.model.Substation;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
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
 * 变电所、区段绑定、功率裁决快照与全局写请求幂等记录的 JDBC 持久化。
 */
@Repository
public class PowerRepository {

    /**
     * 全局写请求幂等记录。
     *
     * @param requestHash  操作+资源+全参数+期望版本规范化后的 SHA-256
     * @param responseJson 首次成功响应快照
     */
    public record RequestRecord(String requestHash, String responseJson) {
    }

    private static final RowMapper<Substation> SUBSTATION_MAPPER = (rs, n) -> new Substation(
            rs.getLong("id"),
            rs.getString("substation_key"),
            rs.getInt("power_cap_kw"),
            rs.getInt("version"));

    private static final RowMapper<SectionBinding> BINDING_MAPPER = (rs, n) -> new SectionBinding(
            rs.getLong("id"),
            rs.getString("section_id"),
            rs.getString("substation_key"),
            rs.getInt("version"));

    private static final RowMapper<PowerAudit> AUDIT_MAPPER = (rs, n) -> new PowerAudit(
            rs.getLong("id"),
            rs.getString("op_type"),
            rs.getString("substation_key"),
            rs.getObject("op_date", LocalDate.class),
            rs.getInt("cap_version"),
            rs.getInt("cap_kw"),
            rs.getString("slices_json"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public PowerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入变电所（版本 1），返回自增主键；业务键冲突抛 DuplicateKeyException 由上层裁决。
     */
    public long insertSubstation(String substationKey, int powerCapKw, long nowMillis) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rail_substation (substation_key, power_cap_kw, version, created_at, updated_at)"
                            + " VALUES (?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, substationKey);
            ps.setInt(2, powerCapKw);
            ps.setLong(3, nowMillis);
            ps.setLong(4, nowMillis);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按业务键查询变电所（不加锁）。
     */
    public Optional<Substation> findSubstationByKey(String substationKey) {
        return jdbc.query("SELECT id, substation_key, power_cap_kw, version FROM rail_substation"
                        + " WHERE substation_key = ?",
                SUBSTATION_MAPPER, substationKey).stream().findFirst();
    }

    /**
     * 按业务键查询变电所并加行级写锁，须在事务内调用。
     */
    public Optional<Substation> findSubstationByKeyForUpdate(String substationKey) {
        return jdbc.query("SELECT id, substation_key, power_cap_kw, version FROM rail_substation"
                        + " WHERE substation_key = ? FOR UPDATE",
                SUBSTATION_MAPPER, substationKey).stream().findFirst();
    }

    /**
     * 更新功率上限与版本。
     */
    public void updateSubstationCap(long id, int powerCapKw, int version, long nowMillis) {
        jdbc.update("UPDATE rail_substation SET power_cap_kw = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                powerCapKw, version, nowMillis, id);
    }

    /**
     * 按区段查询绑定（不加锁）。
     */
    public Optional<SectionBinding> findBinding(String sectionId) {
        return jdbc.query("SELECT id, section_id, substation_key, version FROM rail_section_binding"
                        + " WHERE section_id = ?",
                BINDING_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 按区段查询绑定并加行级写锁，须在事务内调用。
     */
    public Optional<SectionBinding> findBindingForUpdate(String sectionId) {
        return jdbc.query("SELECT id, section_id, substation_key, version FROM rail_section_binding"
                        + " WHERE section_id = ? FOR UPDATE",
                BINDING_MAPPER, sectionId).stream().findFirst();
    }

    /**
     * 批量查询区段绑定。
     */
    public List<SectionBinding> findBindings(Collection<String> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        StringJoiner placeholders = new StringJoiner(", ");
        sectionIds.forEach(s -> placeholders.add("?"));
        return jdbc.query("SELECT id, section_id, substation_key, version FROM rail_section_binding"
                        + " WHERE section_id IN (" + placeholders + ")",
                BINDING_MAPPER, sectionIds.toArray());
    }

    /**
     * 查询绑定到指定变电所的全部区段 ID。
     */
    public List<String> findSectionsBySubstation(String substationKey) {
        return jdbc.query("SELECT section_id FROM rail_section_binding WHERE substation_key = ?"
                        + " ORDER BY section_id",
                (rs, n) -> rs.getString("section_id"), substationKey);
    }

    /**
     * 插入区段绑定（版本 1）；区段唯一键冲突抛 DuplicateKeyException 由上层裁决。
     */
    public void insertBinding(String sectionId, String substationKey, long nowMillis) {
        jdbc.update("INSERT INTO rail_section_binding (section_id, substation_key, version,"
                        + " created_at, updated_at) VALUES (?, ?, 1, ?, ?)",
                sectionId, substationKey, nowMillis, nowMillis);
    }

    /**
     * 更新绑定目标与版本。
     */
    public void updateBinding(long id, String substationKey, int version, long nowMillis) {
        jdbc.update("UPDATE rail_section_binding SET substation_key = ?, version = ?, updated_at = ?"
                        + " WHERE id = ?",
                substationKey, version, nowMillis, id);
    }

    /**
     * 追加功率裁决快照（不可变）；opDate 为 null 表示裁决时无在效占用。
     */
    public void insertAudit(String opType, String substationKey, LocalDate opDate,
                            int capVersion, int capKw, String slicesJson, long nowMillis) {
        jdbc.update("INSERT INTO rail_power_audit"
                        + " (op_type, substation_key, op_date, cap_version, cap_kw, slices_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                opType, substationKey, opDate == null ? null : Date.valueOf(opDate),
                capVersion, capKw, slicesJson, nowMillis);
    }

    /**
     * 按变电所查询裁决快照，按主键升序（即裁决顺序）。
     */
    public List<PowerAudit> findAudits(String substationKey) {
        return jdbc.query("SELECT id, op_type, substation_key, op_date, cap_version, cap_kw,"
                        + " slices_json, created_at FROM rail_power_audit"
                        + " WHERE substation_key = ? ORDER BY id",
                AUDIT_MAPPER, substationKey);
    }

    /**
     * 按全局 requestId 查询写请求幂等记录。
     */
    public Optional<RequestRecord> findRequestRecord(String requestId) {
        return jdbc.query("SELECT request_hash, response_json FROM write_request_record"
                        + " WHERE request_id = ?",
                (rs, n) -> new RequestRecord(rs.getString("request_hash"),
                        rs.getString("response_json")),
                requestId).stream().findFirst();
    }

    /**
     * 写入全局写请求幂等记录；requestId 冲突抛 DuplicateKeyException 由上层裁决。
     */
    public void insertRequestRecord(String requestId, String requestHash, String responseJson,
                                    long nowMillis) {
        jdbc.update("INSERT INTO write_request_record (request_id, request_hash, response_json,"
                        + " created_at) VALUES (?, ?, ?, ?)",
                requestId, requestHash, responseJson, nowMillis);
    }
}
