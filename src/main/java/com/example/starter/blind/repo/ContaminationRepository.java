package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 污染边与闭包版本数据访问。
 * 边以（实验、参与者、操作者）唯一去重；版本保存闭包快照，关闭版本不删除边。
 */
@Repository
public class ContaminationRepository {

    /** 污染边行：actorId 已获知 participantId 的处理代码。 */
    public record EdgeRow(
            long id,
            String experimentId,
            String participantId,
            String actorId,
            String sourceActor,
            String exposureKey,
            long createdAt) {
    }

    /** 闭包版本行：actors 为去重排序后的操作者编号 JSON 数组。 */
    public record VersionRow(
            long id,
            String experimentId,
            String participantId,
            int version,
            String status,
            String actorsJson,
            long createdAt) {
    }

    private static final String EDGE_COLUMNS =
            "id, experiment_id, participant_id, actor_id, source_actor, exposure_key, created_at";

    private static final String VERSION_COLUMNS =
            "id, experiment_id, participant_id, version, status, actors, created_at";

    private static final RowMapper<EdgeRow> EDGE_MAPPER = (rs, n) -> new EdgeRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getString("actor_id"),
            rs.getString("source_actor"),
            rs.getString("exposure_key"),
            rs.getLong("created_at"));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, n) -> new VersionRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("version"),
            rs.getString("status"),
            rs.getString("actors"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public ContaminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 插入一条污染边；重复（实验、参与者、操作者）由唯一索引抛出 DuplicateKeyException。 */
    public void insertEdge(EdgeRow row) {
        jdbc.update("INSERT INTO contamination_edge ("
                        + "experiment_id, participant_id, actor_id, source_actor, exposure_key, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.actorId(), row.sourceActor(),
                row.exposureKey(), row.createdAt());
    }

    /** 查询单条边；不存在返回 null。 */
    public EdgeRow findEdge(String experimentId, String participantId, String actorId) {
        List<EdgeRow> rows = jdbc.query(
                "SELECT " + EDGE_COLUMNS + " FROM contamination_edge "
                        + "WHERE experiment_id = ? AND participant_id = ? AND actor_id = ?",
                EDGE_MAPPER, experimentId, participantId, actorId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定某参与者当前全部边并返回操作者编号，串行化同参与者的闭包变更。 */
    public List<String> lockActorsByParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT actor_id FROM contamination_edge "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY actor_id FOR UPDATE",
                (rs, n) -> rs.getString(1), experimentId, participantId);
    }

    /** 查询某参与者全部边的操作者编号（不加锁）。 */
    public List<String> findActorsByParticipant(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT actor_id FROM contamination_edge "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY actor_id",
                (rs, n) -> rs.getString(1), experimentId, participantId);
    }

    /** 插入新版本；同参与者版本号唯一约束兜底并发。 */
    public void insertVersion(VersionRow row) {
        jdbc.update("INSERT INTO contamination_version ("
                        + "experiment_id, participant_id, version, status, actors, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                row.experimentId(), row.participantId(), row.version(), row.status(),
                row.actorsJson(), row.createdAt());
    }

    /** 行级锁定某参与者最新版本行；无版本时返回 null。 */
    public VersionRow lockLatestVersion(String experimentId, String participantId) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version "
                        + "WHERE experiment_id = ? AND participant_id = ? "
                        + "ORDER BY version DESC LIMIT 1 FOR UPDATE",
                VERSION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某参与者最新版本（不加锁）；无版本时返回 null。 */
    public VersionRow findLatestVersion(String experimentId, String participantId) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version "
                        + "WHERE experiment_id = ? AND participant_id = ? "
                        + "ORDER BY version DESC LIMIT 1",
                VERSION_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 查询某参与者全部版本，按版本号升序。 */
    public List<VersionRow> findVersions(String experimentId, String participantId) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version "
                        + "WHERE experiment_id = ? AND participant_id = ? ORDER BY version",
                VERSION_MAPPER, experimentId, participantId);
    }

    /** 按主键行级锁定版本行。 */
    public VersionRow lockVersionById(long id) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version WHERE id = ? FOR UPDATE",
                VERSION_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 将指定版本置为 CLOSED；仅 OPEN 可关闭，返回受影响行数。 */
    public int markVersionClosed(long id) {
        return jdbc.update(
                "UPDATE contamination_version SET status = 'CLOSED' WHERE id = ? AND status = 'OPEN'",
                id);
    }
}
