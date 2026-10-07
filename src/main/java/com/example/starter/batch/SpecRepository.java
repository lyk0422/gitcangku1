package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 耦合公差规范持久化层：规范版本只追加不修改；测量向量与放行冻结快照只增不改。
 * 特性、约束与向量以 JSON 文本存储，十进制上界与测量值在 JSON 内保持精确文本。
 */
@Repository
public class SpecRepository {

    /**
     * spec_version 表行记录；features/constraintsDef 为 JSON 文本。
     */
    public record SpecRow(long id, String specKey, int version, String featuresJson,
                          String constraintsJson, String operator, String requestId,
                          String createdAt) {
    }

    /**
     * measurement 表行记录；vector 为 JSON 对象文本（特性名→十进制文本值）。
     */
    public record MeasurementRow(long id, String batchKey, int specVersion, String vectorJson,
                                 String outcome, String operator, String requestId,
                                 String createdAt) {
    }

    /**
     * release_freeze 表行记录：最终批准时冻结的完整规范定义与放行依据向量。
     */
    public record FreezeRow(long id, String batchKey, String specKey, int specVersion,
                            String featuresJson, String constraintsJson, String vectorJson,
                            String createdAt) {
    }

    private static final RowMapper<SpecRow> SPEC_MAPPER = (rs, n) -> new SpecRow(
            rs.getLong("id"), rs.getString("spec_key"), rs.getInt("version"),
            rs.getString("features"), rs.getString("constraints_def"),
            rs.getString("operator"), rs.getString("request_id"), rs.getString("created_at"));

    private static final RowMapper<MeasurementRow> MEASUREMENT_MAPPER = (rs, n) -> new MeasurementRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getInt("spec_version"),
            rs.getString("vector"), rs.getString("outcome"), rs.getString("operator"),
            rs.getString("request_id"), rs.getString("created_at"));

    private static final RowMapper<FreezeRow> FREEZE_MAPPER = (rs, n) -> new FreezeRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("spec_key"),
            rs.getInt("spec_version"), rs.getString("features"), rs.getString("constraints_def"),
            rs.getString("vector"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public SpecRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 当前最大版本号；规范不存在时返回空。
     */
    public Optional<Integer> findMaxVersion(String specKey) {
        Integer max = jdbc.queryForObject("SELECT MAX(version) FROM spec_version WHERE spec_key = ?",
                Integer.class, specKey);
        return Optional.ofNullable(max);
    }

    public Optional<SpecRow> findSpec(String specKey, int version) {
        return jdbc.query("SELECT * FROM spec_version WHERE spec_key = ? AND version = ?",
                        SPEC_MAPPER, specKey, version)
                .stream().findFirst();
    }

    public void insertSpec(SpecRow row) {
        jdbc.update("INSERT INTO spec_version (spec_key, version, features, constraints_def,"
                        + " operator, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.specKey(), row.version(), row.featuresJson(), row.constraintsJson(),
                row.operator(), row.requestId(), row.createdAt());
    }

    public void insertMeasurement(MeasurementRow row) {
        jdbc.update("INSERT INTO measurement (batch_key, spec_version, vector, outcome,"
                        + " operator, request_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.specVersion(), row.vectorJson(), row.outcome(),
                row.operator(), row.requestId(), row.createdAt());
    }

    /**
     * 批次全部测量向量，按提交顺序；换版后旧版本记录仍保留在此。
     */
    public List<MeasurementRow> findMeasurements(String batchKey) {
        return jdbc.query("SELECT * FROM measurement WHERE batch_key = ? ORDER BY id",
                MEASUREMENT_MAPPER, batchKey);
    }

    /**
     * 批次在指定规范版本下最近一次 PASS 向量；换版后旧版本的 PASS 不再作为放行依据。
     */
    public Optional<MeasurementRow> findLatestPassMeasurement(String batchKey, int specVersion) {
        return jdbc.query("SELECT * FROM measurement WHERE batch_key = ? AND spec_version = ?"
                        + " AND outcome = 'PASS' ORDER BY id DESC",
                        MEASUREMENT_MAPPER, batchKey, specVersion)
                .stream().findFirst();
    }

    public void insertFreeze(FreezeRow row) {
        jdbc.update("INSERT INTO release_freeze (batch_key, spec_key, spec_version, features,"
                        + " constraints_def, vector, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.specKey(), row.specVersion(), row.featuresJson(),
                row.constraintsJson(), row.vectorJson(), row.createdAt());
    }

    public Optional<FreezeRow> findFreeze(String batchKey) {
        return jdbc.query("SELECT * FROM release_freeze WHERE batch_key = ?",
                        FREEZE_MAPPER, batchKey)
                .stream().findFirst();
    }
}
