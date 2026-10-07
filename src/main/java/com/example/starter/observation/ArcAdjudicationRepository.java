package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 圆弧裁定持久化：arc_adjudication 及其冻结分量（源版本、源圆弧、交集分量）不可变、永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ArcAdjudicationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ArcAdjudicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 原子写入一次裁定的完整冻结结果；adjudicationId 主键冲突时抛出重复键异常。
     */
    public void insert(AdjudicationRecord record) {
        jdbcTemplate.update(
                "INSERT INTO arc_adjudication (adjudication_id, request_id, operator, adjudicated_at_utc, created_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                record.adjudicationId(), record.requestId(), record.operator(),
                Timestamp.from(record.adjudicatedAtUtc()));
        for (AdjudicationRecord.AdjudicationSource source : record.sources()) {
            jdbcTemplate.update(
                    "INSERT INTO arc_adjudication_source (adjudication_id, observation_id, source_version) "
                            + "VALUES (?, ?, ?)",
                    record.adjudicationId(), source.observationId(), source.version());
            for (int i = 0; i < source.arcs().size(); i++) {
                ArcComponent arc = source.arcs().get(i);
                jdbcTemplate.update(
                        "INSERT INTO arc_adjudication_source_arc (adjudication_id, observation_id, arc_index, "
                                + "start_angle, arc_length) VALUES (?, ?, ?, ?, ?)",
                        record.adjudicationId(), source.observationId(), i,
                        arc.startAngle(), arc.arcLength());
            }
        }
        for (int i = 0; i < record.components().size(); i++) {
            ArcComponent component = record.components().get(i);
            jdbcTemplate.update(
                    "INSERT INTO arc_adjudication_component (adjudication_id, component_index, "
                            + "start_angle, arc_length) VALUES (?, ?, ?, ?)",
                    record.adjudicationId(), i, component.startAngle(), component.arcLength());
        }
    }

    /**
     * 按全局唯一 adjudicationId 查询裁定记录（含冻结的源圆弧与交集分量）；不存在时返回空。
     */
    public Optional<AdjudicationRecord> findById(String adjudicationId) {
        record Header(String requestId, String operator, Instant adjudicatedAtUtc) {
        }
        Optional<Header> header = jdbcTemplate.query(
                        "SELECT request_id, operator, adjudicated_at_utc FROM arc_adjudication "
                                + "WHERE adjudication_id = ?",
                        (rs, rowNum) -> new Header(rs.getString("request_id"), rs.getString("operator"),
                                rs.getTimestamp("adjudicated_at_utc").toInstant()),
                        adjudicationId)
                .stream().findFirst();
        if (header.isEmpty()) {
            return Optional.empty();
        }
        List<AdjudicationRecord.AdjudicationSource> sources = jdbcTemplate.query(
                        "SELECT observation_id, source_version FROM arc_adjudication_source "
                                + "WHERE adjudication_id = ? ORDER BY observation_id ASC",
                        (rs, rowNum) -> new AdjudicationRecord.AdjudicationSource(
                                rs.getString("observation_id"), rs.getInt("source_version"),
                                findSourceArcs(adjudicationId, rs.getString("observation_id"))),
                        adjudicationId);
        List<ArcComponent> components = jdbcTemplate.query(
                "SELECT start_angle, arc_length FROM arc_adjudication_component "
                        + "WHERE adjudication_id = ? ORDER BY component_index ASC",
                (rs, rowNum) -> new ArcComponent(rs.getInt("start_angle"), rs.getInt("arc_length")),
                adjudicationId);
        return Optional.of(new AdjudicationRecord(adjudicationId, header.get().requestId(),
                header.get().operator(), header.get().adjudicatedAtUtc(), sources, components));
    }

    private List<ArcComponent> findSourceArcs(String adjudicationId, String observationId) {
        return jdbcTemplate.query(
                "SELECT start_angle, arc_length FROM arc_adjudication_source_arc "
                        + "WHERE adjudication_id = ? AND observation_id = ? ORDER BY arc_index ASC",
                (rs, rowNum) -> new ArcComponent(rs.getInt("start_angle"), rs.getInt("arc_length")),
                adjudicationId, observationId);
    }
}
