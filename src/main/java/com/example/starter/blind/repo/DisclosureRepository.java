package com.example.starter.blind.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 泄露传播数据访问：披露事件（exposureKey 唯一）、操作者—参与者有向边、闭包版本快照。
 * 边去重由唯一索引 uq_disclosure_edge 兜底；版本只追加不覆盖。
 */
@Repository
public class DisclosureRepository {

    /** 披露事件行。 */
    public record EventRow(
            String exposureKey,
            String experimentId,
            String requestId,
            String sourceActor,
            long createdAt) {
    }

    /** 有向边行：source 直接向 target 披露了 participant 的处理代码。 */
    public record EdgeRow(
            long id,
            String experimentId,
            String participantId,
            String sourceActor,
            String targetActor,
            String exposureKey,
            long createdAt) {
    }

    /** 闭包版本行；actorsJson 为有序去重操作者编号 JSON，不含处理代码。 */
    public record ClosureVersionRow(
            long id,
            String experimentId,
            String participantId,
            int versionNo,
            String status,
            String actorsJson,
            int edgeCount,
            long createdAt,
            Long closedAt) {
    }

    private static final RowMapper<EventRow> EVENT_MAPPER = (rs, n) -> new EventRow(
            rs.getString("exposure_key"),
            rs.getString("experiment_id"),
            rs.getString("request_id"),
            rs.getString("source_actor"),
            rs.getLong("created_at"));

    private static final RowMapper<EdgeRow> EDGE_MAPPER = (rs, n) -> new EdgeRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("source_actor"),
            rs.getString("target_actor"),
            rs.getString("exposure_key"),
            rs.getLong("created_at"));

    private static final RowMapper<ClosureVersionRow> VERSION_MAPPER = (rs, n) ->
            new ClosureVersionRow(
                    rs.getLong("id"),
                    rs.getString("experiment_id"),
                    rs.getString("participant_id"),
                    rs.getInt("version_no"),
                    rs.getString("status"),
                    rs.getString("actors_json"),
                    rs.getInt("edge_count"),
                    rs.getLong("created_at"),
                    (Long) rs.getObject("closed_at"));

    private static final String VERSION_COLUMNS =
            "id, experiment_id, participant_id, version_no, status, actors_json, edge_count, "
                    + "created_at, closed_at";

    private final JdbcTemplate jdbc;

    public DisclosureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 披露事件 ----------------

    /**
     * 插入披露事件；exposureKey 全局唯一，重复使用由主键触发 DuplicateKeyException。
     */
    public void insertEvent(EventRow row) {
        jdbc.update("INSERT INTO disclosure_event "
                        + "(exposure_key, experiment_id, request_id, source_actor, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.exposureKey(), row.experimentId(), row.requestId(),
                row.sourceActor(), row.createdAt());
    }

    public EventRow findEvent(String exposureKey) {
        List<EventRow> rows = jdbc.query(
                "SELECT exposure_key, experiment_id, request_id, source_actor, created_at "
                        + "FROM disclosure_event WHERE exposure_key = ?",
                EVENT_MAPPER, exposureKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------- 有向边 ----------------

    /**
     * 插入一条披露边；重复（同实验/参与者/来源/接收人）由唯一索引触发 DuplicateKeyException。
     */
    public void insertEdge(EdgeRow row) {
        jdbc.update("INSERT INTO disclosure_edge "
                        + "(experiment_id, participant_id, source_actor, target_actor, "
                        + "exposure_key, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.sourceActor(), row.targetActor(),
                row.exposureKey(), row.createdAt());
    }

    public boolean edgeExists(String experimentId, String participantId,
                              String sourceActor, String targetActor) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = ? AND participant_id = ? "
                        + "AND source_actor = ? AND target_actor = ?",
                Integer.class, experimentId, participantId, sourceActor, targetActor);
        return count != null && count > 0;
    }

    /**
     * 行级锁定该参与者的全部边，保证闭包计算与版本快照按提交顺序串行；
     * 调用方须先持有参与者分配行锁，此处 FOR UPDATE 仅做二次屏障。
     */
    public List<EdgeRow> lockEdges(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, source_actor, target_actor, "
                        + "exposure_key, created_at FROM disclosure_edge "
                        + "WHERE experiment_id = ? AND participant_id = ? FOR UPDATE",
                EDGE_MAPPER, experimentId, participantId);
    }

    public List<EdgeRow> findEdges(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, source_actor, target_actor, "
                        + "exposure_key, created_at FROM disclosure_edge "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY id",
                EDGE_MAPPER, experimentId, participantId);
    }

    public int countEdges(String experimentId, String participantId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM disclosure_edge WHERE experiment_id = ? AND participant_id = ?",
                Integer.class, experimentId, participantId);
        return count == null ? 0 : count;
    }

    // ---------------- 闭包版本 ----------------

    public void insertVersion(ClosureVersionRow row) {
        jdbc.update("INSERT INTO closure_version "
                        + "(experiment_id, participant_id, version_no, status, actors_json, "
                        + "edge_count, created_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                row.experimentId(), row.participantId(), row.versionNo(), row.status(),
                row.actorsJson(), row.edgeCount(), row.createdAt());
    }

    /**
     * 行级锁定最新版本行；不存在返回 null。
     */
    public ClosureVersionRow lockLatestVersion(String experimentId, String participantId) {
        List<ClosureVersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM closure_version cv "
                        + "WHERE id = (SELECT MAX(id) FROM closure_version "
                        + "WHERE experiment_id = ? AND participant_id = ?) FOR UPDATE",
                VERSION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ClosureVersionRow findLatestVersion(String experimentId, String participantId) {
        List<ClosureVersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM closure_version cv "
                        + "WHERE id = (SELECT MAX(id) FROM closure_version "
                        + "WHERE experiment_id = ? AND participant_id = ?)",
                VERSION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ClosureVersionRow lockVersionById(long versionId) {
        List<ClosureVersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM closure_version WHERE id = ? FOR UPDATE",
                VERSION_MAPPER, versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ClosureVersionRow findVersion(String experimentId, String participantId, int versionNo) {
        List<ClosureVersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM closure_version "
                        + "WHERE experiment_id = ? AND participant_id = ? AND version_no = ?",
                VERSION_MAPPER, experimentId, participantId, versionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ClosureVersionRow> findVersions(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM closure_version "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY version_no",
                VERSION_MAPPER, experimentId, participantId);
    }

    /**
     * 冻结版本：仅 OPEN 可关闭。
     *
     * @return 受影响行数；0 表示版本不存在或已关闭
     */
    public int markClosed(long versionId, long closedAt) {
        return jdbc.update("UPDATE closure_version SET status = 'CLOSED', closed_at = ? "
                + "WHERE id = ? AND status = 'OPEN'", closedAt, versionId);
    }

    /** 唯一索引冲突判定：exposureKey 被其他请求使用。 */
    public boolean isDuplicateExposureKey(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("pk_disclosure_event");
    }

    /** 唯一索引冲突判定：边已存在（并发下的兜底）。 */
    public boolean isDuplicateEdge(DuplicateKeyException e) {
        return e.getMessage() != null && e.getMessage().contains("uq_disclosure_edge");
    }
}
