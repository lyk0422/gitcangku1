package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 容量转配单与冻结证据数据访问（仅激活成功才写入，整体不可变）。
 */
@Repository
public class TransferRepository {

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 transferKey 查询转配单；不存在返回 null。 */
    public TransferPo findTransfer(String transferKey) {
        List<TransferPo> rows = jdbc.query(
                "SELECT transfer_key, request_id, item_count, activated_at "
                        + "FROM capacity_transfer WHERE transfer_key = ?",
                (rs, n) -> new TransferPo(rs.getString("transfer_key"), rs.getString("request_id"),
                        rs.getInt("item_count"), rs.getLong("activated_at")),
                transferKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新增转配单主记录（调用方负责事务；transferKey 唯一冲突由数据库兜底）。 */
    public void insertTransfer(TransferPo po) {
        jdbc.update("INSERT INTO capacity_transfer "
                        + "(transfer_key, request_id, item_count, activated_at) "
                        + "VALUES (?, ?, ?, ?)",
                po.transferKey(), po.requestId(), po.itemCount(), po.activatedAt());
    }

    /** 新增航线级证据（调用方负责事务）。 */
    public void insertRouteEvidence(TransferRouteEvidencePo po) {
        jdbc.update("INSERT INTO transfer_route_evidence "
                        + "(transfer_key, route_id, from_version, to_version, review_id, "
                        + "review_route_version, review_airspace_version, before_plan, after_plan) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.routeId(), po.fromVersion(), po.toVersion(), po.reviewId(),
                po.reviewRouteVersion(), po.reviewAirspaceVersion(),
                encodePlan(po.beforePlan()), encodePlan(po.afterPlan()));
    }

    /** 新增桶级证据（调用方负责事务）。 */
    public void insertBucketEvidence(TransferBucketEvidencePo po) {
        jdbc.update("INSERT INTO transfer_bucket_evidence "
                        + "(transfer_key, cell_x, cell_y, bucket_start, max_flights, "
                        + "used_before, used_after) VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.cellX(), po.cellY(), po.bucketStart(), po.maxFlights(),
                po.usedBefore(), po.usedAfter());
    }

    /** 查询某转配单的全部航线级证据（按 routeId 稳定排序）。 */
    public List<TransferRouteEvidencePo> findRouteEvidences(String transferKey) {
        return jdbc.query(
                "SELECT transfer_key, route_id, from_version, to_version, review_id, "
                        + "review_route_version, review_airspace_version, before_plan, after_plan "
                        + "FROM transfer_route_evidence WHERE transfer_key = ? ORDER BY route_id",
                (rs, n) -> new TransferRouteEvidencePo(
                        rs.getString("transfer_key"), rs.getString("route_id"),
                        rs.getInt("from_version"), rs.getInt("to_version"),
                        rs.getString("review_id"), rs.getInt("review_route_version"),
                        rs.getLong("review_airspace_version"),
                        decodePlan(rs.getString("before_plan")),
                        decodePlan(rs.getString("after_plan"))),
                transferKey);
    }

    /** 查询某转配单的全部桶级证据（按时间桶与单元稳定排序）。 */
    public List<TransferBucketEvidencePo> findBucketEvidences(String transferKey) {
        return jdbc.query(
                "SELECT transfer_key, cell_x, cell_y, bucket_start, max_flights, "
                        + "used_before, used_after FROM transfer_bucket_evidence "
                        + "WHERE transfer_key = ? ORDER BY bucket_start, cell_x, cell_y",
                (rs, n) -> new TransferBucketEvidencePo(
                        rs.getString("transfer_key"), rs.getInt("cell_x"), rs.getInt("cell_y"),
                        rs.getLong("bucket_start"), rs.getInt("max_flights"),
                        rs.getInt("used_before"), rs.getInt("used_after")),
                transferKey);
    }

    /** 序列编码："seq:cellX,cellY,bucketStart;..."；空序列编码为空串。 */
    public static String encodePlan(List<PlanSlotPo> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            PlanSlotPo item = items.get(i);
            sb.append(item.seq()).append(':').append(item.cellX()).append(',')
                    .append(item.cellY()).append(',').append(item.bucketStart());
        }
        return sb.toString();
    }

    /** 序列解码。 */
    public static List<PlanSlotPo> decodePlan(String text) {
        List<PlanSlotPo> items = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String token : text.split(";")) {
                int colon = token.indexOf(':');
                int comma1 = token.indexOf(',', colon + 1);
                int comma2 = token.indexOf(',', comma1 + 1);
                items.add(new PlanSlotPo(
                        Integer.parseInt(token.substring(0, colon)),
                        Integer.parseInt(token.substring(colon + 1, comma1)),
                        Integer.parseInt(token.substring(comma1 + 1, comma2)),
                        Long.parseLong(token.substring(comma2 + 1))));
            }
        }
        return items;
    }
}
