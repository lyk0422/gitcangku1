package com.example.starter.firmware.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 紧急例外放行记录数据访问。历史只增不改。
 */
@Repository
public class FreezeExceptionRepository {

    /**
     * 一条紧急例外放行记录。
     */
    public record ExceptionRecord(long id, String api, String incidentId, List<String> approvers,
                                  String ref, String createdAtUtc) {
    }

    private static final RowMapper<ExceptionRecord> MAPPER = (rs, rowNum) -> new ExceptionRecord(
            rs.getLong("id"), rs.getString("api"), rs.getString("incident_id"),
            FreezeOrderRepository.toList(rs.getString("approvers_csv")),
            rs.getString("ref"), rs.getString("created_at_utc"));

    private final JdbcTemplate jdbc;

    public FreezeExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String api, String incidentId, String approversCsv, String ref, String createdAtUtc) {
        jdbc.update("INSERT INTO freeze_exception_record (api, incident_id, approvers_csv, ref, created_at_utc)"
                + " VALUES (?, ?, ?, ?, ?)", api, incidentId, approversCsv, ref, createdAtUtc);
    }

    public List<ExceptionRecord> findAll() {
        return jdbc.query("SELECT id, api, incident_id, approvers_csv, ref, created_at_utc"
                + " FROM freeze_exception_record ORDER BY id", MAPPER);
    }
}
