package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 特性规范持久化层：规范版本/特性/约束/系数、测量向量与放行冻结快照。
 * 所有 SQL 参数化；十进制上界与向量以原文字符串存取，判断在内存中用 BigDecimal 精确完成。
 */
@Repository
public class SpecRepository {

    /**
     * spec_measurement 表行记录；batchStatus 为该测量落定后的批次状态快照。
     */
    public record MeasurementRow(long id, String batchKey, String measurementKey, String specKey,
                                 int specVersion, String outcome, String batchStatus,
                                 String operator, String vectorJson, String createdAt) {
    }

    private static final RowMapper<MeasurementRow> MEASUREMENT_MAPPER = (rs, n) -> new MeasurementRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("measurement_key"),
            rs.getString("spec_key"), rs.getInt("spec_version"), rs.getString("outcome"),
            rs.getString("batch_status"), rs.getString("operator"),
            rs.getString("vector_json"), rs.getString("created_at"));

    private final JdbcTemplate jdbc;

    public SpecRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSpecVersion(String specKey, int version, String createdBy, String createdAt) {
        jdbc.update("INSERT INTO spec_version (spec_key, version, created_by, created_at)"
                + " VALUES (?, ?, ?, ?)", specKey, version, createdBy, createdAt);
    }

    /**
     * 某规范当前最大版本号；没有任何版本时为空。
     */
    public Optional<Integer> findMaxVersion(String specKey) {
        Integer max = jdbc.queryForObject(
                "SELECT MAX(version) FROM spec_version WHERE spec_key = ?",
                Integer.class, specKey);
        return Optional.ofNullable(max);
    }

    public boolean existsVersion(String specKey, int version) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM spec_version WHERE spec_key = ? AND version = ?",
                Integer.class, specKey, version);
        return count != null && count > 0;
    }

    public void insertFeature(String specKey, int version, int seq, String featureName) {
        jdbc.update("INSERT INTO spec_feature (spec_key, version, seq, feature_name)"
                + " VALUES (?, ?, ?, ?)", specKey, version, seq, featureName);
    }

    /**
     * 插入约束并返回自增主键，供系数行关联。
     */
    public long insertConstraint(String specKey, int version, int seq, String upperBound) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO spec_constraint (spec_key, version, seq, upper_bound)"
                            + " VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, specKey);
            ps.setInt(2, version);
            ps.setInt(3, seq);
            ps.setString(4, upperBound);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public void insertCoefficient(long constraintId, int featureSeq, int coefficient) {
        jdbc.update("INSERT INTO spec_constraint_coeff (constraint_id, feature_seq, coefficient)"
                + " VALUES (?, ?, ?)", constraintId, featureSeq, coefficient);
    }

    /**
     * 加载一版规范完整定义：特性按 seq 排序，约束按 seq 排序，系数按 feature_seq 排序。
     */
    public Optional<SpecDefinition> loadSpec(String specKey, int version) {
        if (!existsVersion(specKey, version)) {
            return Optional.empty();
        }
        List<String> features = jdbc.queryForList(
                "SELECT feature_name FROM spec_feature WHERE spec_key = ? AND version = ? ORDER BY seq",
                String.class, specKey, version);
        record ConstraintHead(long id, int seq, String upperBound) {
        }
        List<ConstraintHead> heads = jdbc.query(
                "SELECT id, seq, upper_bound FROM spec_constraint WHERE spec_key = ? AND version = ?"
                        + " ORDER BY seq",
                (rs, n) -> new ConstraintHead(rs.getLong("id"), rs.getInt("seq"),
                        rs.getString("upper_bound")), specKey, version);
        List<SpecDefinition.LinearConstraint> constraints = new ArrayList<>(heads.size());
        for (ConstraintHead head : heads) {
            List<Integer> coefficients = jdbc.queryForList(
                    "SELECT coefficient FROM spec_constraint_coeff WHERE constraint_id = ?"
                            + " ORDER BY feature_seq",
                    Integer.class, head.id());
            constraints.add(new SpecDefinition.LinearConstraint(coefficients,
                    new BigDecimal(head.upperBound())));
        }
        return Optional.of(new SpecDefinition(specKey, version, features, constraints));
    }

    public void insertMeasurement(MeasurementRow row) {
        jdbc.update("INSERT INTO spec_measurement (batch_key, measurement_key, spec_key,"
                        + " spec_version, outcome, batch_status, operator, vector_json, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.batchKey(), row.measurementKey(), row.specKey(), row.specVersion(),
                row.outcome(), row.batchStatus(), row.operator(), row.vectorJson(), row.createdAt());
    }

    public Optional<MeasurementRow> findMeasurement(String batchKey, String measurementKey) {
        return jdbc.query("SELECT * FROM spec_measurement WHERE batch_key = ? AND measurement_key = ?",
                        MEASUREMENT_MAPPER, batchKey, measurementKey)
                .stream().findFirst();
    }

    public List<MeasurementRow> findMeasurements(String batchKey) {
        return jdbc.query("SELECT * FROM spec_measurement WHERE batch_key = ? ORDER BY id",
                MEASUREMENT_MAPPER, batchKey);
    }

    /**
     * 批次在指定规范版本下的 PASS 测量向量（放行依据）；每个版本至多一条有效 PASS。
     */
    public Optional<MeasurementRow> findPassMeasurement(String batchKey, String specKey,
                                                        int specVersion) {
        return jdbc.query("SELECT * FROM spec_measurement WHERE batch_key = ? AND spec_key = ?"
                                + " AND spec_version = ? AND outcome = 'PASS' ORDER BY id",
                        MEASUREMENT_MAPPER, batchKey, specKey, specVersion)
                .stream().findFirst();
    }

    public void insertFreeze(String batchKey, String specKey, int specVersion,
                             String constraintsJson, String vectorJson, String createdAt) {
        jdbc.update("INSERT INTO release_freeze (batch_key, spec_key, spec_version,"
                        + " constraints_json, vector_json, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                batchKey, specKey, specVersion, constraintsJson, vectorJson, createdAt);
    }

    /**
     * 批次的放行冻结快照是否存在。
     */
    public boolean existsFreeze(String batchKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_freeze WHERE batch_key = ?",
                Integer.class, batchKey);
        return count != null && count > 0;
    }
}
