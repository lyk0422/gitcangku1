package com.example.starter.evidence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 容器巡检单与见证人表访问。巡检单按 request_id 唯一；
 * 第二见证人到达时须先 {@link #findByRequestIdForUpdate} 锁定巡检单行，使并发见证串行。
 */
@Repository
public class ContainerInspectionRepository {

    private static final InspectionRowMapper INSPECTION_MAPPER = new InspectionRowMapper();
    private static final WitnessRowMapper WITNESS_MAPPER = new WitnessRowMapper();

    private final JdbcTemplate jdbc;

    public ContainerInspectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 第一见证人建单：记录见证版本快照与其封条结论，初始 AWAITING_WITNESS，不改变任何证物状态。
     */
    public void insert(String requestId, String containerKey, int sealVersion, SealResult sealResult,
                       LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO container_inspection
                            (request_id, container_key, container_seal_version, seal_result,
                             status, completed_response, completed_at, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, ?)
                        """,
                requestId, containerKey, sealVersion, sealResult.name(),
                InspectionStatus.AWAITING_WITNESS.name(), now);
    }

    /**
     * 按主键查询巡检单（不加锁）。
     */
    public Optional<ContainerInspection> findById(long id) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE id = ?", INSPECTION_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 按 requestId 查询巡检单（不加锁）。
     */
    public Optional<ContainerInspection> findByRequestId(String requestId) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE request_id = ?", INSPECTION_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 按 requestId 查询并锁定巡检单行（SELECT ... FOR UPDATE）。
     */
    public Optional<ContainerInspection> findByRequestIdForUpdate(String requestId) {
        List<ContainerInspection> rows = jdbc.query(
                "SELECT * FROM container_inspection WHERE request_id = ? FOR UPDATE",
                INSPECTION_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 双人确认完成：写入最终状态、完成时刻与首次完成响应体（含首次隔离清单，重放原样返回）。
     */
    public void complete(long id, InspectionStatus status, String completedResponse,
                         LocalDateTime completedAt) {
        jdbc.update("""
                        UPDATE container_inspection
                        SET status = ?, completed_response = ?, completed_at = ?
                        WHERE id = ?
                        """,
                status.name(), completedResponse, completedAt, id);
    }

    /**
     * 两名见证人结论或版本不一致：巡检单作废（CONFLICTED），不改变任何证物状态。
     */
    public void markConflicted(long id, LocalDateTime completedAt) {
        jdbc.update("UPDATE container_inspection SET status = ?, completed_at = ? WHERE id = ?",
                InspectionStatus.CONFLICTED.name(), completedAt, id);
    }

    /**
     * 追加见证人；同一巡检单同一保管人只能见证一次（唯一约束拒绝重复）。
     */
    public void insertWitness(long inspectionId, String witnessId, SealResult sealResult, String note,
                              LocalDateTime now) {
        jdbc.update("""
                        INSERT INTO inspection_witness
                            (inspection_id, witness_id, seal_result, note, created_at)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                inspectionId, witnessId, sealResult.name(), note, now);
    }

    /**
     * 按提交顺序查询巡检单全部见证人。
     */
    public List<InspectionWitness> findWitnesses(long inspectionId) {
        return jdbc.query(
                "SELECT * FROM inspection_witness WHERE inspection_id = ? ORDER BY id",
                WITNESS_MAPPER, inspectionId);
    }

    /**
     * 按容器查询全部巡检单（按发生顺序）。
     */
    public List<ContainerInspection> findByContainerKey(String containerKey) {
        return jdbc.query(
                "SELECT * FROM container_inspection WHERE container_key = ? ORDER BY id",
                INSPECTION_MAPPER, containerKey);
    }

    private static final class InspectionRowMapper implements RowMapper<ContainerInspection> {
        @Override
        public ContainerInspection mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ContainerInspection(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("container_key"),
                    rs.getInt("container_seal_version"),
                    SealResult.valueOf(rs.getString("seal_result")),
                    InspectionStatus.valueOf(rs.getString("status")),
                    rs.getString("completed_response"),
                    rs.getObject("completed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }

    private static final class WitnessRowMapper implements RowMapper<InspectionWitness> {
        @Override
        public InspectionWitness mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new InspectionWitness(
                    rs.getLong("id"),
                    rs.getLong("inspection_id"),
                    rs.getString("witness_id"),
                    SealResult.valueOf(rs.getString("seal_result")),
                    rs.getString("note"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
