package com.example.starter.coral;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 样方、移植、复核与封存快照的数据访问。所有SQL使用参数绑定。
 */
@Repository
public class CoralRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CoralRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 样方行记录。 */
    public record PlotRow(long id, String plotCode, int capacity, PlotStatus status) {
    }

    /** 移植记录行。 */
    public record TransplantRow(long id, String requestId, String plantCode, String nursery,
            long plotId, Instant transplantedAt, TransplantStatus status) {
    }

    /** 复核记录行。 */
    public record ReviewRow(long id, String requestId, long transplantId,
            TransplantStatus result, Instant reviewedAt) {
    }

    /** 封存快照行。 */
    public record SealRow(long id, String requestId, long plotId, Instant sealedAt,
            int aliveCount, int deadCount, int pendingCount, List<SealPlantItem> plants) {
    }

    private static final RowMapper<PlotRow> PLOT_MAPPER = (rs, n) -> mapPlot(rs);

    private static final RowMapper<TransplantRow> TRANSPLANT_MAPPER = (rs, n) -> new TransplantRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("plant_code"),
            rs.getString("nursery"), rs.getLong("plot_id"),
            rs.getTimestamp("transplanted_at").toInstant(),
            TransplantStatus.valueOf(rs.getString("status")));

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, n) -> new ReviewRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getLong("transplant_id"),
            TransplantStatus.valueOf(rs.getString("result")),
            rs.getTimestamp("reviewed_at").toInstant());

    private static PlotRow mapPlot(ResultSet rs) throws SQLException {
        return new PlotRow(rs.getLong("id"), rs.getString("plot_code"), rs.getInt("capacity"),
                PlotStatus.valueOf(rs.getString("status")));
    }

    /** 按编号查询样方并加行级写锁，用于序列化容量与封存竞争。 */
    public PlotRow findPlotForUpdate(String plotCode) {
        List<PlotRow> rows = jdbc.query(
                "SELECT id, plot_code, capacity, status FROM plot WHERE plot_code = ? FOR UPDATE",
                PLOT_MAPPER, plotCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键查询样方并加行级写锁。 */
    public PlotRow findPlotByIdForUpdate(long plotId) {
        List<PlotRow> rows = jdbc.query(
                "SELECT id, plot_code, capacity, status FROM plot WHERE id = ? FOR UPDATE",
                PLOT_MAPPER, plotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按主键查询样方，不加锁。 */
    public PlotRow findPlotById(long plotId) {
        List<PlotRow> rows = jdbc.query(
                "SELECT id, plot_code, capacity, status FROM plot WHERE id = ?",
                PLOT_MAPPER, plotId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    /** 按编号查询样方，不加锁。 */
    public PlotRow findPlotByCode(String plotCode) {
        List<PlotRow> rows = jdbc.query(
                "SELECT id, plot_code, capacity, status FROM plot WHERE plot_code = ?",
                PLOT_MAPPER, plotCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 统计样方内活跃移植体数量（存活或待复核，不含死亡）。 */
    public long countActiveTransplants(long plotId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transplant WHERE plot_id = ? AND status <> 'DEAD'",
                Long.class, plotId);
        return count == null ? 0L : count;
    }

    /** 按幂等编号查询移植记录。 */
    public TransplantRow findTransplantByRequestId(String requestId) {
        List<TransplantRow> rows = jdbc.query(
                "SELECT * FROM transplant WHERE request_id = ?", TRANSPLANT_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 按植株编号查询移植记录。 */
    public TransplantRow findTransplantByPlantCode(String plantCode) {
        List<TransplantRow> rows = jdbc.query(
                "SELECT * FROM transplant WHERE plant_code = ?", TRANSPLANT_MAPPER, plantCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增移植记录，初始状态为待复核。 */
    public void insertTransplant(String requestId, String plantCode, String nursery,
            long plotId, Instant transplantedAt) {
        jdbc.update(
                "INSERT INTO transplant (request_id, plant_code, nursery, plot_id, transplanted_at, status)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING')",
                requestId, plantCode, nursery, plotId, Timestamp.from(transplantedAt));
    }

    /** 更新移植记录当前状态。 */
    public void updateTransplantStatus(long transplantId, TransplantStatus status) {
        jdbc.update("UPDATE transplant SET status = ? WHERE id = ?",
                status.name(), transplantId);
    }

    /** 查询样方全部移植记录（含死亡），按主键排序保证清单稳定。 */
    public List<TransplantRow> findTransplantsByPlotId(long plotId) {
        return jdbc.query("SELECT * FROM transplant WHERE plot_id = ? ORDER BY id",
                TRANSPLANT_MAPPER, plotId);
    }

    /** 按幂等编号查询复核记录。 */
    public ReviewRow findReviewByRequestId(String requestId) {
        List<ReviewRow> rows = jdbc.query(
                "SELECT * FROM review WHERE request_id = ?", REVIEW_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增复核记录。 */
    public void insertReview(String requestId, long transplantId, TransplantStatus result,
            Instant reviewedAt) {
        jdbc.update(
                "INSERT INTO review (request_id, transplant_id, result, reviewed_at) VALUES (?, ?, ?, ?)",
                requestId, transplantId, result.name(), Timestamp.from(reviewedAt));
    }

    /** 查询单株复核链，按复核时刻与主键排序。 */
    public List<ReviewRow> findReviewsByTransplantId(long transplantId) {
        return jdbc.query(
                "SELECT * FROM review WHERE transplant_id = ? ORDER BY reviewed_at, id",
                REVIEW_MAPPER, transplantId);
    }

    /** 按样方查询封存快照。 */
    public SealRow findSealByPlotId(long plotId) {
        List<SealRow> rows = jdbc.query(
                "SELECT * FROM plot_seal WHERE plot_id = ?", this::mapSeal, plotId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入封存快照并标记样方已封存。 */
    public void insertSeal(String requestId, long plotId, Instant sealedAt, int aliveCount,
            int deadCount, int pendingCount, List<SealPlantItem> plants) {
        jdbc.update(
                "INSERT INTO plot_seal (request_id, plot_id, sealed_at, alive_count, dead_count,"
                        + " pending_count, plant_list) VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, plotId, Timestamp.from(sealedAt), aliveCount, deadCount, pendingCount,
                writePlants(plants));
        jdbc.update("UPDATE plot SET status = 'SEALED', sealed_at = ? WHERE id = ?",
                Timestamp.from(sealedAt), plotId);
    }

    private SealRow mapSeal(ResultSet rs, int rowNum) throws SQLException {
        return new SealRow(rs.getLong("id"), rs.getString("request_id"), rs.getLong("plot_id"),
                rs.getTimestamp("sealed_at").toInstant(), rs.getInt("alive_count"),
                rs.getInt("dead_count"), rs.getInt("pending_count"),
                readPlants(rs.getString("plant_list")));
    }

    private String writePlants(List<SealPlantItem> plants) {
        try {
            return objectMapper.writeValueAsString(plants);
        } catch (Exception e) {
            throw new IllegalStateException("封存清单序列化失败", e);
        }
    }

    private List<SealPlantItem> readPlants(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<SealPlantItem>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("封存清单反序列化失败", e);
        }
    }
}