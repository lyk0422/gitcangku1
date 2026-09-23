package com.example.starter.blind.repo;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 污染传播数据访问：污染主体、闭包版本、去重有向边、披露流水与隔离单。
 * 所有跨表判定均围绕 contamination_subject 行锁串行化；边只增不删。
 */
@Repository
public class ContaminationRepository {

    /** 揭盲批准生成持密种子边时使用的系统披露源标识，非真实操作者。 */
    public static final String SEED_SOURCE = "#UNBLINDING";

    /** 污染主体行；每个被揭盲参与者至多一行。 */
    public record SubjectRow(
            long id,
            String experimentId,
            String participantId,
            int currentVersion) {
    }

    /** 闭包版本行；closure 为操作者升序 JSON 数组字符串，不含处理代码。 */
    public record VersionRow(
            long id,
            long subjectId,
            int versionNo,
            String status,
            String closure,
            int edgeCount,
            long createdAt,
            Long frozenAt,
            String quarantineId) {
    }

    /** 去重有向披露边。 */
    public record EdgeRow(
            long id,
            long subjectId,
            String sourceActor,
            String targetActor,
            String edgeKind,
            long createdAt) {
    }

    /** 隔离单行。 */
    public record QuarantineRow(
            String id,
            String experimentId,
            String participantId,
            long subjectId,
            int versionNo,
            long versionId,
            String closureSnapshot,
            String initiatorActor,
            String confirmerActor,
            String status,
            long createdAt,
            Long confirmedAt) {
    }

    private static final RowMapper<SubjectRow> SUBJECT_MAPPER = (rs, n) -> new SubjectRow(
            rs.getLong("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getInt("current_version"));

    private static final RowMapper<VersionRow> VERSION_MAPPER = (rs, n) -> new VersionRow(
            rs.getLong("id"),
            rs.getLong("subject_id"),
            rs.getInt("version_no"),
            rs.getString("status"),
            rs.getString("closure"),
            rs.getInt("edge_count"),
            rs.getLong("created_at"),
            (Long) rs.getObject("frozen_at"),
            rs.getString("quarantine_id"));

    private static final RowMapper<EdgeRow> EDGE_MAPPER = (rs, n) -> new EdgeRow(
            rs.getLong("id"),
            rs.getLong("subject_id"),
            rs.getString("source_actor"),
            rs.getString("target_actor"),
            rs.getString("edge_kind"),
            rs.getLong("created_at"));

    private static final RowMapper<QuarantineRow> QUARANTINE_MAPPER = (rs, n) -> new QuarantineRow(
            rs.getString("id"),
            rs.getString("experiment_id"),
            rs.getString("participant_id"),
            rs.getLong("subject_id"),
            rs.getInt("version_no"),
            rs.getLong("version_id"),
            rs.getString("closure_snapshot"),
            rs.getString("initiator_actor"),
            rs.getString("confirmer_actor"),
            rs.getString("status"),
            rs.getLong("created_at"),
            (Long) rs.getObject("confirmed_at"));

    private static final String VERSION_COLUMNS =
            "id, subject_id, version_no, status, closure, edge_count, created_at, frozen_at, quarantine_id";

    private final JdbcTemplate jdbc;

    public ContaminationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- 污染主体 ----------------

    /** 行级锁定污染主体，串行化同参与者的披露/门禁/隔离并发；不存在返回 null。 */
    public SubjectRow lockSubject(String experimentId, String participantId) {
        List<SubjectRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, current_version "
                        + "FROM contamination_subject "
                        + "WHERE experiment_id = ? AND participant_id = ? FOR UPDATE",
                SUBJECT_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertSubject(String experimentId, String participantId, int currentVersion) {
        jdbc.update("INSERT INTO contamination_subject "
                        + "(experiment_id, participant_id, current_version) VALUES (?, ?, ?)",
                experimentId, participantId, currentVersion);
    }

    /** 普通查询污染主体（不加锁）；不存在返回 null。 */
    public SubjectRow findSubject(String experimentId, String participantId) {
        List<SubjectRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, current_version "
                        + "FROM contamination_subject "
                        + "WHERE experiment_id = ? AND participant_id = ?",
                SUBJECT_MAPPER, experimentId, participantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateSubjectVersion(long subjectId, int currentVersion) {
        jdbc.update("UPDATE contamination_subject SET current_version = ? WHERE id = ?",
                currentVersion, subjectId);
    }

    // ---------------- 版本 ----------------

    public void insertVersion(VersionRow row) {
        jdbc.update("INSERT INTO contamination_version ("
                        + "subject_id, version_no, status, closure, edge_count, created_at, "
                        + "frozen_at, quarantine_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)",
                row.subjectId(), row.versionNo(), row.status(), row.closure(), row.edgeCount(),
                row.createdAt());
    }

    public VersionRow findVersionById(long versionId) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version WHERE id = ?",
                VERSION_MAPPER, versionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定主体当前指向的版本。 */
    public VersionRow lockCurrentVersion(long subjectId, int currentVersionNo) {
        List<VersionRow> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version "
                        + "WHERE subject_id = ? AND version_no = ? FOR UPDATE",
                VERSION_MAPPER, subjectId, currentVersionNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 开放版本快照刷新（新披露并入后更新边数与闭包）。 */
    public void updateOpenSnapshot(long versionId, int edgeCount, String closure) {
        jdbc.update("UPDATE contamination_version SET edge_count = ?, closure = ? "
                + "WHERE id = ? AND status = 'OPEN'", edgeCount, closure, versionId);
    }

    /** 隔离确认：冻结版本，仅 OPEN 可冻结。 */
    public int freezeVersion(long versionId, String quarantineId, long frozenAt) {
        return jdbc.update("UPDATE contamination_version SET status = 'CLOSED', frozen_at = ?, "
                        + "quarantine_id = ? WHERE id = ? AND status = 'OPEN'",
                frozenAt, quarantineId, versionId);
    }

    public List<VersionRow> listVersionsBySubject(long subjectId) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM contamination_version "
                        + "WHERE subject_id = ? ORDER BY version_no",
                VERSION_MAPPER, subjectId);
    }

    // ---------------- 有向边 ----------------

    public void insertEdge(EdgeRow row) {
        jdbc.update("INSERT INTO exposure_edge "
                        + "(subject_id, source_actor, target_actor, edge_kind, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                row.subjectId(), row.sourceActor(), row.targetActor(), row.edgeKind(), row.createdAt());
    }

    public EdgeRow findEdge(long subjectId, String sourceActor, String targetActor) {
        List<EdgeRow> rows = jdbc.query(
                "SELECT id, subject_id, source_actor, target_actor, edge_kind, created_at "
                        + "FROM exposure_edge WHERE subject_id = ? AND source_actor = ? AND target_actor = ?",
                EDGE_MAPPER, subjectId, sourceActor, targetActor);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EdgeRow> listEdges(long subjectId) {
        return jdbc.query(
                "SELECT id, subject_id, source_actor, target_actor, edge_kind, created_at "
                        + "FROM exposure_edge WHERE subject_id = ? ORDER BY id",
                EDGE_MAPPER, subjectId);
    }

    public void insertExposureRecord(long subjectId, long edgeId, String sourceActor,
                                     String targetActor, String recordedBy, long createdAt) {
        jdbc.update("INSERT INTO exposure_record ("
                        + "subject_id, edge_id, source_actor, target_actor, recorded_by, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)",
                subjectId, edgeId, sourceActor, targetActor, recordedBy, createdAt);
    }

    // ---------------- 隔离单 ----------------

    public void insertQuarantine(QuarantineRow row) {
        jdbc.update("INSERT INTO quarantine_order ("
                        + "id, experiment_id, participant_id, subject_id, version_no, version_id, "
                        + "closure_snapshot, initiator_actor, confirmer_actor, status, created_at, "
                        + "confirmed_at, pending_subject_id"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, 'OPEN', ?, NULL, ?)",
                row.id(), row.experimentId(), row.participantId(), row.subjectId(),
                row.versionNo(), row.versionId(), row.closureSnapshot(), row.initiatorActor(),
                row.createdAt(), row.subjectId());
    }

    public QuarantineRow findQuarantineById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, subject_id, version_no, version_id, "
                        + "closure_snapshot, initiator_actor, confirmer_actor, status, created_at, confirmed_at "
                        + "FROM quarantine_order WHERE id = ?",
                QUARANTINE_MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定隔离单。 */
    public QuarantineRow lockQuarantineById(String orderId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, subject_id, version_no, version_id, "
                        + "closure_snapshot, initiator_actor, confirmer_actor, status, created_at, confirmed_at "
                        + "FROM quarantine_order WHERE id = ? FOR UPDATE",
                QUARANTINE_MAPPER, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 该主体是否存在待确认隔离单（唯一稀疏占位 pending_subject_id 保证至多一个）。 */
    public QuarantineRow findOpenQuarantineBySubject(long subjectId) {
        List<QuarantineRow> rows = jdbc.query(
                "SELECT id, experiment_id, participant_id, subject_id, version_no, version_id, "
                        + "closure_snapshot, initiator_actor, confirmer_actor, status, created_at, confirmed_at "
                        + "FROM quarantine_order WHERE pending_subject_id = ? AND status = 'OPEN'",
                QUARANTINE_MAPPER, subjectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 确认隔离：写入确认人与时间并释放待确认占位；仅 OPEN 可确认。
     *
     * @return 受影响行数；0 表示不存在或已确认
     */
    public int confirmQuarantine(String orderId, String confirmerActor, long confirmedAt) {
        return jdbc.update("UPDATE quarantine_order SET status = 'CONFIRMED', confirmer_actor = ?, "
                        + "confirmed_at = ?, pending_subject_id = NULL "
                        + "WHERE id = ? AND status = 'OPEN'",
                confirmerActor, confirmedAt, orderId);
    }

    public List<QuarantineRow> listQuarantineBySubject(long subjectId) {
        return jdbc.query(
                "SELECT id, experiment_id, participant_id, subject_id, version_no, version_id, "
                        + "closure_snapshot, initiator_actor, confirmer_actor, status, created_at, confirmed_at "
                        + "FROM quarantine_order WHERE subject_id = ? ORDER BY created_at, id",
                QUARANTINE_MAPPER, subjectId);
    }
}
