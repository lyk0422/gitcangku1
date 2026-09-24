package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 改航候选评估不可变记录数据访问。
 * 点列以 "x,y;x,y" 文本保存，命中 zoneId 以逗号拼接，均为不可变内容。
 */
@Repository
public class EvaluationRepository {

    private final JdbcTemplate jdbc;

    public EvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<EvaluationPo> EVALUATION_MAPPER = (rs, n) -> new EvaluationPo(
            rs.getString("evaluation_id"),
            rs.getString("evaluation_key"),
            rs.getString("route_id"),
            rs.getInt("route_version"),
            rs.getInt("new_route_version"),
            rs.getLong("airspace_version"),
            rs.getInt("selected_index"),
            ReviewRepository.decodePoints(rs.getString("selected_points")),
            rs.getLong("created_at"));

    private static final RowMapper<EvaluationCandidatePo> CANDIDATE_MAPPER = (rs, n) ->
            new EvaluationCandidatePo(
                    rs.getString("evaluation_id"),
                    rs.getInt("idx"),
                    rs.getString("conclusion"),
                    ReviewRepository.decodeZoneIds(rs.getString("hit_zone_ids")),
                    ReviewRepository.decodePoints(rs.getString("points")));

    /** 插入不可变评估记录（调用方负责事务，与航线替换同一事务提交）。 */
    public void insertEvaluation(EvaluationPo po) {
        jdbc.update("INSERT INTO evaluation "
                        + "(evaluation_id, evaluation_key, route_id, route_version, new_route_version, "
                        + "airspace_version, selected_index, selected_points, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.evaluationId(), po.evaluationKey(), po.routeId(), po.routeVersion(),
                po.newRouteVersion(), po.airspaceVersion(), po.selectedIndex(),
                ReviewRepository.encodePoints(po.selectedPoints()), po.createdAt());
    }

    /** 插入逐候选不可变结论（调用方负责事务）。 */
    public void insertCandidate(EvaluationCandidatePo po) {
        jdbc.update("INSERT INTO evaluation_candidate "
                        + "(evaluation_id, idx, conclusion, hit_zone_ids, points) "
                        + "VALUES (?, ?, ?, ?, ?)",
                po.evaluationId(), po.idx(), po.conclusion(),
                ReviewRepository.encodeZoneIds(po.hitZoneIds()),
                ReviewRepository.encodePoints(po.points()));
    }

    /** 按 evaluationId 查询不可变评估记录，不存在返回 null。 */
    public EvaluationPo findEvaluation(String evaluationId) {
        return jdbc.query(
                "SELECT evaluation_id, evaluation_key, route_id, route_version, new_route_version, "
                        + "airspace_version, selected_index, selected_points, created_at "
                        + "FROM evaluation WHERE evaluation_id = ?",
                EVALUATION_MAPPER, evaluationId).stream().findFirst().orElse(null);
    }

    /** 查询某评估的全部逐候选结论（按声明序号升序）。 */
    public List<EvaluationCandidatePo> findCandidates(String evaluationId) {
        return jdbc.query(
                "SELECT evaluation_id, idx, conclusion, hit_zone_ids, points "
                        + "FROM evaluation_candidate WHERE evaluation_id = ? ORDER BY idx",
                CANDIDATE_MAPPER, evaluationId);
    }
}
