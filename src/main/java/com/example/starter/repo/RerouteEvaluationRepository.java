package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.ReviewConclusion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 改航候选集评估不可变记录数据访问。
 *
 * <p>主表固化空域/航线版本与选中序号，候选子表保存每个候选的点列与命中 zoneId。
 * 点快照以 "x,y;x,y" 文本保存，命中 zoneId 字典序去重后逗号拼接，均为不可变内容。</p>
 */
@Repository
public class RerouteEvaluationRepository {

    private final JdbcTemplate jdbc;

    public RerouteEvaluationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入不可变评估记录（含全部候选结论，调用方负责事务）。 */
    public void insertEvaluation(RerouteEvaluationPo po) {
        jdbc.update("INSERT INTO reroute_evaluation "
                        + "(evaluation_key, route_id, route_version, new_route_version, "
                        + "airspace_version, selected_index, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.evaluationKey(), po.routeId(), po.routeVersion(), po.newRouteVersion(),
                po.airspaceVersion(), po.selectedIndex(), po.requestId(), po.createdAt());
        for (CandidateResultPo c : po.candidates()) {
            String conclusion = c.hitZoneIds().isEmpty()
                    ? ReviewConclusion.CLEAR.name()
                    : ReviewConclusion.BLOCKED.name();
            jdbc.update("INSERT INTO reroute_evaluation_candidate "
                            + "(evaluation_key, candidate_index, conclusion, hit_zone_ids, points_snapshot) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    po.evaluationKey(), c.index(), conclusion,
                    ReviewRepository.encodeZoneIds(c.hitZoneIds()),
                    ReviewRepository.encodePoints(c.points()));
        }
    }

    /** 按 evaluationKey 查询不可变评估记录（含逐候选结论），不存在返回 null。 */
    public RerouteEvaluationPo findEvaluation(String evaluationKey) {
        return jdbc.query(
                "SELECT evaluation_key, route_id, route_version, new_route_version, "
                        + "airspace_version, selected_index, request_id, created_at "
                        + "FROM reroute_evaluation WHERE evaluation_key = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    String key = rs.getString("evaluation_key");
                    String routeId = rs.getString("route_id");
                    int routeVersion = rs.getInt("route_version");
                    int newRouteVersion = rs.getInt("new_route_version");
                    long airspaceVersion = rs.getLong("airspace_version");
                    int selectedIndex = rs.getInt("selected_index");
                    String requestId = rs.getString("request_id");
                    long createdAt = rs.getLong("created_at");
                    List<CandidateResultPo> candidates = findCandidates(key);
                    return new RerouteEvaluationPo(key, routeId, routeVersion, newRouteVersion,
                            airspaceVersion, selectedIndex, candidates, requestId, createdAt);
                }, evaluationKey);
    }

    private List<CandidateResultPo> findCandidates(String evaluationKey) {
        return jdbc.query(
                "SELECT candidate_index, conclusion, hit_zone_ids, points_snapshot "
                        + "FROM reroute_evaluation_candidate WHERE evaluation_key = ? "
                        + "ORDER BY candidate_index",
                (rs, n) -> {
                    List<Point> points = ReviewRepository.decodePoints(
                            rs.getString("points_snapshot"));
                    List<String> hitZoneIds = ReviewRepository.decodeZoneIds(
                            rs.getString("hit_zone_ids"));
                    return new CandidateResultPo(rs.getInt("candidate_index"),
                            points, hitZoneIds);
                }, evaluationKey);
    }
}
