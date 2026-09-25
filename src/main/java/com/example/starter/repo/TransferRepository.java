package com.example.starter.repo;

import com.example.starter.domain.Bucket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * 容量转配单及冻结证据数据访问。
 * 穿越路径以 "cell@bucketStart;cell@bucketStart" 文本保存，内容不可变。
 */
@Repository
public class TransferRepository {

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 transferKey 查询转配单头，不存在返回 null。 */
    public TransferPo findTransfer(String transferKey) {
        return jdbc.query(
                        "SELECT transfer_key, request_id, created_at FROM capacity_transfer "
                                + "WHERE transfer_key = ?",
                        (rs, n) -> new TransferPo(rs.getString("transfer_key"),
                                rs.getString("request_id"), rs.getLong("created_at")),
                        transferKey)
                .stream().findFirst().orElse(null);
    }

    /** 插入转配单头（调用方负责事务）。 */
    public void insertTransfer(TransferPo po) {
        jdbc.update("INSERT INTO capacity_transfer (transfer_key, request_id, created_at) "
                + "VALUES (?, ?, ?)", po.transferKey(), po.requestId(), po.createdAt());
    }

    /** 按 transferKey 查询转配项（按 seq 升序）。 */
    public List<TransferItemPo> findItems(String transferKey) {
        return jdbc.query(
                "SELECT transfer_key, seq, route_id, expected_version, source_cell, source_bucket, "
                        + "target_cell, target_bucket FROM capacity_transfer_item "
                        + "WHERE transfer_key = ? ORDER BY seq",
                (rs, n) -> new TransferItemPo(rs.getString("transfer_key"), rs.getInt("seq"),
                        rs.getString("route_id"), rs.getInt("expected_version"),
                        rs.getString("source_cell"), rs.getLong("source_bucket"),
                        rs.getString("target_cell"), rs.getLong("target_bucket")),
                transferKey);
    }

    /** 插入转配项（调用方负责事务）。 */
    public void insertItem(TransferItemPo po) {
        jdbc.update("INSERT INTO capacity_transfer_item "
                        + "(transfer_key, seq, route_id, expected_version, source_cell, source_bucket, "
                        + "target_cell, target_bucket) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.seq(), po.routeId(), po.expectedVersion(),
                po.sourceCell(), po.sourceBucket(), po.targetCell(), po.targetBucket());
    }

    /** 按 transferKey 查询逐航线冻结证据（按 route_id 升序，稳定排序）。 */
    public List<TransferRoutePo> findRoutes(String transferKey) {
        return jdbc.query(
                "SELECT transfer_key, route_id, old_version, new_version, review_id, "
                        + "before_path, after_path FROM capacity_transfer_route "
                        + "WHERE transfer_key = ? ORDER BY route_id",
                (rs, n) -> new TransferRoutePo(rs.getString("transfer_key"), rs.getString("route_id"),
                        rs.getInt("old_version"), rs.getInt("new_version"),
                        rs.getString("review_id"),
                        decodePath(rs.getString("before_path")),
                        decodePath(rs.getString("after_path"))),
                transferKey);
    }

    /** 插入逐航线冻结证据（调用方负责事务）。 */
    public void insertRoute(TransferRoutePo po) {
        jdbc.update("INSERT INTO capacity_transfer_route "
                        + "(transfer_key, route_id, old_version, new_version, review_id, "
                        + "before_path, after_path) VALUES (?, ?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.routeId(), po.oldVersion(), po.newVersion(), po.reviewId(),
                encodePath(po.beforePath()), encodePath(po.afterPath()));
    }

    /** 按 transferKey 查询受影响桶余量冻结（按 cell_id、bucket_start 升序，稳定排序）。 */
    public List<TransferBucketPo> findBuckets(String transferKey) {
        return jdbc.query(
                "SELECT transfer_key, cell_id, bucket_start, max_flights, before_count, after_count "
                        + "FROM capacity_transfer_bucket WHERE transfer_key = ? "
                        + "ORDER BY cell_id, bucket_start",
                (rs, n) -> new TransferBucketPo(rs.getString("transfer_key"), rs.getString("cell_id"),
                        rs.getLong("bucket_start"), (Integer) rs.getObject("max_flights"),
                        rs.getInt("before_count"), rs.getInt("after_count")),
                transferKey);
    }

    /** 插入受影响桶余量冻结（调用方负责事务）。 */
    public void insertBucket(TransferBucketPo po) {
        jdbc.update("INSERT INTO capacity_transfer_bucket "
                        + "(transfer_key, cell_id, bucket_start, max_flights, before_count, after_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                po.transferKey(), po.cellId(), po.bucketStart(), po.maxFlights(),
                po.beforeCount(), po.afterCount());
    }

    /** 穿越路径编码："cell@bucketStart;cell@bucketStart"；空列表编码为空串。 */
    public static String encodePath(List<Bucket> path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(path.get(i).cellId()).append('@').append(path.get(i).bucketStart());
        }
        return sb.toString();
    }

    /** 穿越路径解码，空串返回空列表。 */
    public static List<Bucket> decodePath(String text) {
        List<Bucket> path = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            for (String entry : text.split(";")) {
                int at = entry.lastIndexOf('@');
                path.add(new Bucket(entry.substring(0, at),
                        Long.parseLong(entry.substring(at + 1))));
            }
        }
        return path;
    }
}
