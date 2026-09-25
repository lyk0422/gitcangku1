package com.example.starter.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 外部机构配置版本与回执的 JDBC 仓储。
 * 所有写路径均处于先锁定事件行的写事务内；(incident_id, version) 与
 * (incident_id, config_version, agency_code) 唯一约束兜底并发写入。
 * 回执只插入不更新：历史回执不可改写，替换配置后旧回执仅归属旧版本。
 */
@Repository
public class AgencyRepository {

    private final JdbcTemplate jdbc;

    public AgencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<AgencyConfig> CONFIG_MAPPER = (rs, n) -> mapConfig(rs);
    private static final RowMapper<AgencyReceipt> RECEIPT_MAPPER = (rs, n) -> new AgencyReceipt(
            rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("config_version"),
            rs.getString("agency_code"), AgencyReceiptType.valueOf(rs.getString("receipt_type")),
            rs.getString("reason"), rs.getString("ack_key"),
            rs.getTimestamp("created_at").toInstant());

    private static AgencyConfig mapConfig(ResultSet rs) throws SQLException {
        String joined = rs.getString("agency_codes");
        List<String> codes = joined.isEmpty() ? List.of() : Arrays.asList(joined.split(","));
        return new AgencyConfig(rs.getLong("id"), rs.getLong("incident_id"), rs.getInt("version"),
                codes, AgencyConfigStatus.valueOf(rs.getString("status")),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }

    /**
     * 插入新配置版本（ACTIVE），agencyCodes 须已去重排序，返回生成主键。
     */
    public long insertConfig(long incidentId, int version, List<String> agencyCodes,
                             String createdBy, Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        String joined = String.join(",", agencyCodes);
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_agency_configs (incident_id, version, agency_codes,"
                            + " status, created_by, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, incidentId);
            ps.setInt(2, version);
            ps.setString(3, joined);
            ps.setString(4, AgencyConfigStatus.ACTIVE.name());
            ps.setString(5, createdBy);
            ps.setTimestamp(6, Timestamp.from(now));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 查询事件当前生效（ACTIVE）的配置版本；从未配置时为空。
     */
    public Optional<AgencyConfig> findActiveConfig(long incidentId) {
        List<AgencyConfig> rows = jdbc.query(
                "SELECT * FROM incident_agency_configs WHERE incident_id = ? AND status = 'ACTIVE'",
                CONFIG_MAPPER, incidentId);
        return rows.stream().findFirst();
    }

    /**
     * 将配置版本标记为 REPLACED（替换配置时同事务执行，旧回执仍归属该版本）。
     */
    public void markReplaced(long configId) {
        jdbc.update("UPDATE incident_agency_configs SET status = 'REPLACED' WHERE id = ?", configId);
    }

    /**
     * 插入终态回执（CONFIRM/REJECT），返回生成主键。
     * (incident_id, config_version, agency_code) 唯一约束兜底同机构同版本重复回执。
     */
    public long insertReceipt(AgencyReceipt receipt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO incident_agency_receipts (incident_id, config_version, agency_code,"
                            + " receipt_type, reason, ack_key, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, receipt.incidentId());
            ps.setInt(2, receipt.configVersion());
            ps.setString(3, receipt.agencyCode());
            ps.setString(4, receipt.type().name());
            ps.setString(5, receipt.reason());
            ps.setString(6, receipt.ackKey());
            ps.setTimestamp(7, Timestamp.from(receipt.createdAt()));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按机构与配置版本查询回执，用于终态唯一性校验。
     */
    public Optional<AgencyReceipt> findReceipt(long incidentId, int configVersion, String agencyCode) {
        List<AgencyReceipt> rows = jdbc.query(
                "SELECT * FROM incident_agency_receipts WHERE incident_id = ?"
                        + " AND config_version = ? AND agency_code = ?",
                RECEIPT_MAPPER, incidentId, configVersion, agencyCode);
        return rows.stream().findFirst();
    }

    /**
     * 查询事件指定配置版本的全部回执，按落库顺序返回。
     */
    public List<AgencyReceipt> listReceipts(long incidentId, int configVersion) {
        return jdbc.query("SELECT * FROM incident_agency_receipts WHERE incident_id = ?"
                        + " AND config_version = ? ORDER BY id",
                RECEIPT_MAPPER, incidentId, configVersion);
    }
}
