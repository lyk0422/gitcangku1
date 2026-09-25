package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 外部机构配置版本与终态回执的 JDBC 仓储。
 * 所有写调用均处于先锁定事件行的写事务内；回执 (incident_id, config_version, agency_code)
 * 唯一约束兜底"同一机构每版本一条终态回执"，配置 (incident_id, version) 唯一约束兜底版本递增。
 * 机构代码列表以排序后的 JSON 数组存储。
 */
@Repository
public class AgencyRepository {

    private static final TypeReference<List<String>> CODE_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AgencyRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<AgencyConfig> configMapper = (rs, n) -> new AgencyConfig(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
            readCodes(rs.getString("agency_codes")),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());

    private final RowMapper<AgencyAck> ackMapper = (rs, n) -> new AgencyAck(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("config_version"),
            rs.getString("agency_code"), AgencyAckType.valueOf(rs.getString("ack_type")),
            rs.getString("reason"), rs.getString("submitted_by"),
            rs.getTimestamp("submitted_at").toInstant());

    private List<String> readCodes(String json) {
        try {
            return objectMapper.readValue(json, CODE_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("机构代码列表反序列化失败: " + json, e);
        }
    }

    private String writeCodes(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(codes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("机构代码列表序列化失败", e);
        }
    }

    /**
     * 新增一个配置版本，返回生成主键。(incident_id, version) 唯一约束兜底并发版本递增。
     */
    public long insertConfig(AgencyConfig config) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_agency_configs (incident_id, version, agency_codes,"
                            + " created_by, created_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, config.incidentId());
            ps.setInt(2, config.version());
            ps.setString(3, writeCodes(config.agencyCodes()));
            ps.setString(4, config.createdBy());
            ps.setTimestamp(5, Timestamp.from(config.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询指定版本配置（不存在返回空）。
     */
    public Optional<AgencyConfig> findConfig(long incidentId, int version) {
        List<AgencyConfig> rows = jdbc.query(
                "SELECT * FROM incident_agency_configs WHERE incident_id = ? AND version = ?",
                configMapper, incidentId, version);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件最新配置版本（version 最大）；从未配置返回空。
     */
    public Optional<AgencyConfig> findLatestConfig(long incidentId) {
        List<AgencyConfig> rows = jdbc.query(
                "SELECT * FROM incident_agency_configs WHERE incident_id = ?"
                        + " ORDER BY version DESC LIMIT 1",
                configMapper, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件全部配置版本，按版本号升序返回（含历史版本）。
     */
    public List<AgencyConfig> listConfigs(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_agency_configs WHERE incident_id = ? ORDER BY version",
                configMapper, incidentId);
    }

    /**
     * 插入一条终态回执，返回生成主键。
     * 唯一约束 (incident_id, config_version, agency_code) 拒绝同机构同版本重复回执。
     */
    public long insertAck(AgencyAck ack) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_agency_acks (incident_id, config_version, agency_code,"
                            + " ack_type, reason, submitted_by, submitted_at)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ack.incidentId());
            ps.setInt(2, ack.configVersion());
            ps.setString(3, ack.agencyCode());
            ps.setString(4, ack.ackType().name());
            ps.setString(5, ack.reason());
            ps.setString(6, ack.submittedBy());
            ps.setTimestamp(7, Timestamp.from(ack.submittedAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询指定机构在指定版本的终态回执（不存在返回空）。
     */
    public Optional<AgencyAck> findAck(long incidentId, int version, String agencyCode) {
        List<AgencyAck> rows = jdbc.query(
                "SELECT * FROM incident_agency_acks WHERE incident_id = ? AND config_version = ?"
                        + " AND agency_code = ?",
                ackMapper, incidentId, version, agencyCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询指定版本的全部终态回执，按机构代码排序返回。
     */
    public List<AgencyAck> listAcksByVersion(long incidentId, int version) {
        return jdbc.query(
                "SELECT * FROM incident_agency_acks WHERE incident_id = ? AND config_version = ?"
                        + " ORDER BY agency_code",
                ackMapper, incidentId, version);
    }

    /**
     * 查询事件全部历史回执（跨所有版本），先按版本再按机构代码排序返回。
     */
    public List<AgencyAck> listAllAcks(long incidentId) {
        return jdbc.query(
                "SELECT * FROM incident_agency_acks WHERE incident_id = ?"
                        + " ORDER BY config_version, agency_code",
                ackMapper, incidentId);
    }

    /**
     * 判断指定版本是否存在任一拒绝回执。
     */
    public boolean existsReject(long incidentId, int version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_agency_acks WHERE incident_id = ?"
                        + " AND config_version = ? AND ack_type = 'REJECT'",
                Integer.class, incidentId, version);
        return count != null && count > 0;
    }
}
