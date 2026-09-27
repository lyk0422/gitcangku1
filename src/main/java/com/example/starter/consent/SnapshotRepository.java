package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.consent.dto.CrossQueryResponse.QueryItem;
import com.example.starter.consent.dto.SnapshotSummary;

/**
 * 跨域查询快照的持久化访问：快照写入后冻结，后续迁移、撤回、清除均不改写。
 */
@Repository
public class SnapshotRepository {

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("snapshot_id"),
            rs.getString("request_id"),
            rs.getString("allowed_regions"),
            rs.getInt("requested_count"),
            rs.getInt("item_count"),
            toInstant(rs.getTimestamp("created_at")));

    private static final RowMapper<QueryItem> ITEM_MAPPER = (rs, rowNum) -> new QueryItem(
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getInt("consent_version"),
            rs.getString("region_code"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private final JdbcTemplate jdbc;

    public SnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查询快照头行。
     *
     * @param snapshotId     快照标识
     * @param requestId      生成本快照的幂等请求标识
     * @param allowedRegions 允许区域集合，按字典序逗号连接
     * @param requestedCount 请求查询的对象数（要求值）
     * @param itemCount      固化对象数（实际值）
     * @param createdAt      快照生成时间（UTC）
     */
    public record SnapshotRow(String snapshotId, String requestId, String allowedRegions,
                              int requestedCount, int itemCount, Instant createdAt) {

        /**
         * 允许区域集合按字典序的列表视图。
         */
        public List<String> allowedRegionList() {
            if (allowedRegions.isEmpty()) {
                return List.of();
            }
            return Arrays.asList(allowedRegions.split(","));
        }
    }

    void insertSnapshot(String snapshotId, String requestId, String allowedRegions,
                        int requestedCount, int itemCount, Instant createdAt) {
        jdbc.update(
                "INSERT INTO query_snapshot (snapshot_id, request_id, allowed_regions,"
                        + " requested_count, item_count, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                snapshotId, requestId, allowedRegions, requestedCount, itemCount, Timestamp.from(createdAt));
    }

    void insertItem(String snapshotId, int itemSeq, QueryItem item) {
        jdbc.update(
                "INSERT INTO query_snapshot_item (snapshot_id, item_seq, subject_key, purpose, epoch,"
                        + " consent_version, region_code, record_key, payload) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshotId, itemSeq, item.subjectKey(), item.purpose().name(), item.epoch(),
                item.consentVersion(), item.regionCode(), item.recordKey(), item.payload());
    }

    Optional<SnapshotRow> findSnapshot(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT snapshot_id, request_id, allowed_regions, requested_count, item_count, created_at"
                        + " FROM query_snapshot WHERE snapshot_id = ?",
                SNAPSHOT_MAPPER, snapshotId);
        return rows.stream().findFirst();
    }

    /**
     * 快照条目按条目序号升序返回，保证与生成时顺序一致。
     */
    List<QueryItem> findItems(String snapshotId) {
        return jdbc.query(
                "SELECT subject_key, purpose, epoch, consent_version, region_code, record_key, payload"
                        + " FROM query_snapshot_item WHERE snapshot_id = ? ORDER BY item_seq",
                ITEM_MAPPER, snapshotId);
    }

    /**
     * 快照历史：按快照序号（提交顺序）稳定排序；from/to 均为 UTC，区间左闭右开 [from, to)。
     */
    List<SnapshotSummary> findHistory(Instant from, Instant to) {
        return jdbc.query(
                "SELECT snapshot_id, allowed_regions, requested_count, item_count, created_at"
                        + " FROM query_snapshot"
                        + " WHERE created_at >= ? AND created_at < ?"
                        + " ORDER BY snapshot_seq",
                (rs, rowNum) -> new SnapshotSummary(
                        rs.getString("snapshot_id"),
                        toInstant(rs.getTimestamp("created_at")),
                        Arrays.asList(rs.getString("allowed_regions").split(",")),
                        rs.getInt("requested_count"),
                        rs.getInt("item_count")),
                Timestamp.from(from), Timestamp.from(to));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
