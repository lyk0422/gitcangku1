package com.example.starter.calibration.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.BudgetRevision;
import com.example.starter.calibration.model.UncertaintyComponent;

/**
 * 预算修订历史持久化。只增不改；每条修订固化版本号、合成不确定度、计算口径与分量快照。
 */
@Repository
public class BudgetRevisionRepository {

    private static final RowMapper<UncertaintyComponent> COMPONENT_MAPPER = (rs, rowNum) ->
            new UncertaintyComponent(
                    rs.getLong("id"),
                    rs.getLong("revision_id"),
                    rs.getString("component_name"),
                    rs.getBigDecimal("component_value"),
                    null);

    private final JdbcTemplate jdbc;

    public BudgetRevisionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条预算修订及其分量快照（须在持有测量行锁的事务内调用）。
     */
    public void insert(long measurementId, int version, BigDecimal combinedUncertainty, String formula,
                       String changedBy, String requestId, Instant createdAt,
                       List<ComponentRepository.ComponentValue> components) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO budget_revision "
                            + "(measurement_id, version, combined_uncertainty, formula, changed_by, request_id, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, measurementId);
            ps.setInt(2, version);
            ps.setBigDecimal(3, combinedUncertainty);
            ps.setString(4, formula);
            ps.setString(5, changedBy);
            ps.setString(6, requestId);
            ps.setObject(7, JdbcTimes.toDb(createdAt));
            return ps;
        }, keyHolder);
        long revisionId = keyHolder.getKey().longValue();
        for (ComponentRepository.ComponentValue component : components) {
            jdbc.update("INSERT INTO budget_revision_component (revision_id, component_name, component_value) "
                            + "VALUES (?, ?, ?)",
                    revisionId, component.name(), component.value());
        }
    }

    /**
     * 查询某测量的全部预算修订（按版本升序），每条附带按名称排序的分量快照。
     */
    public List<BudgetRevision> findByMeasurementId(long measurementId) {
        return jdbc.query("SELECT * FROM budget_revision WHERE measurement_id = ? ORDER BY version",
                (rs, rowNum) -> {
                    long revisionId = rs.getLong("id");
                    List<UncertaintyComponent> components = jdbc.query(
                            "SELECT * FROM budget_revision_component WHERE revision_id = ? "
                                    + "ORDER BY component_name",
                            COMPONENT_MAPPER, revisionId);
                    return new BudgetRevision(
                            revisionId,
                            rs.getLong("measurement_id"),
                            rs.getInt("version"),
                            rs.getBigDecimal("combined_uncertainty"),
                            rs.getString("formula"),
                            rs.getString("changed_by"),
                            rs.getString("request_id"),
                            JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                            components);
                }, measurementId);
    }
}
