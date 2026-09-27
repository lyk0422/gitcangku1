package com.example.starter.consent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 驻留区域与查询快照的持久化访问，基于 JdbcTemplate，所有查询使用参数化 SQL。
 * 快照一旦写入即冻结，不提供任何更新或删除操作。
 */
@Repository
public class ResidencyRepository {

    private static final RowMapper<RegionRow> REGION_MAPPER = (rs, rowNum) -> new RegionRow(
            rs.getString("region_code"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SnapshotRow> SNAPSHOT_MAPPER = (rs, rowNum) -> new SnapshotRow(
            rs.getString("snapshot_id"),
            rs.getString("request_id"),
            rs.getString("subject_key"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getInt("epoch"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SnapshotItemRow> ITEM_MAPPER = (rs, rowNum) -> new SnapshotItemRow(
            rs.getString("snapshot_id"),
            rs.getString("record_key"),
            rs.getInt("epoch"),
            rs.getString("region_code"),
            rs.getInt("consent_version"),
            rs.getString("payload"),
            rs.getInt("item_index"));

    private final JdbcTemplate jdbc;

    public ResidencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 驻留区域行。
     *
     * @param regionCode 区域代码，全局唯一
     * @param createdAt  登记时间（UTC）
     */
    public record RegionRow(String regionCode, Instant createdAt) {
    }

    /**
     * 查询快照头行。
     *
     * @param snapshotId 快照标识
     * @param requestId  生成本快照的幂等请求标识
     * @param subjectKey 主体标识
     * @param purpose    用途
     * @param epoch      快照时的授权代次
     * @param createdAt  快照生成时间（UTC）
     */
    public record SnapshotRow(String snapshotId, String requestId, String subjectKey,
                              Purpose purpose, int epoch, Instant createdAt) {
    }

    /**
     * 查询快照明细行：固化对象的代次、驻留区域与授权版本。
     *
     * @param snapshotId     所属快照标识
     * @param recordKey      记录键
     * @param epoch          对象所属授权代次（快照时固化）
     * @param regionCode     对象驻留区域（快照时固化），未绑定区域为 NULL
     * @param consentVersion 授权版本（快照时固化）
     * @param payload        对象内容（快照时固化），已清除对象为 NULL
     * @param itemIndex      快照内排序序号，从 0 开始
     */
    public record SnapshotItemRow(String snapshotId, String recordKey, int epoch, String regionCode,
                                  int consentVersion, String payload, int itemIndex) {
    }

    Optional<RegionRow> findRegion(String regionCode) {
        List<RegionRow> rows = jdbc.query(
                "SELECT region_code, created_at FROM residency_region WHERE region_code = ?",
                REGION_MAPPER, regionCode);
        return rows.stream().findFirst();
    }

    List<RegionRow> findAllRegions() {
        return jdbc.query(
                "SELECT region_code, created_at FROM residency_region ORDER BY region_code",
                REGION_MAPPER);
    }

    void insertRegion(String regionCode, String requestId, Instant createdAt) {
        jdbc.update(
                "INSERT INTO residency_region (region_code, request_id, created_at) VALUES (?, ?, ?)",
                regionCode, requestId, Timestamp.from(createdAt));
    }

    Optional<SnapshotRow> findSnapshot(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query(
                "SELECT snapshot_id, request_id, subject_key, purpose, epoch, created_at"
                        + " FROM consent_snapshot WHERE snapshot_id = ?",
                SNAPSHOT_MAPPER, snapshotId);
        return rows.stream().findFirst();
    }

    /**
     * 按主体与用途查询快照历史，时间区间左闭右开 [from, to)（UTC），from/to 为 NULL 表示不限制；
     * 按生成时间、快照标识升序，保证顺序稳定。
     */
    List<SnapshotRow> findSnapshots(String subjectKey, Purpose purpose, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder(
                "SELECT snapshot_id, request_id, subject_key, purpose, epoch, created_at"
                        + " FROM consent_snapshot WHERE subject_key = ? AND purpose = ?");
        List<Object> args = new ArrayList<>();
        args.add(subjectKey);
        args.add(purpose.name());
        if (from != null) {
            sql.append(" AND created_at >= ?");
            args.add(Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND created_at < ?");
            args.add(Timestamp.from(to));
        }
        sql.append(" ORDER BY created_at, snapshot_id");
        return jdbc.query(sql.toString(), SNAPSHOT_MAPPER, args.toArray());
    }

    void insertSnapshot(String snapshotId, String requestId, String subjectKey, Purpose purpose,
                        int epoch, Instant createdAt) {
        jdbc.update(
                "INSERT INTO consent_snapshot (snapshot_id, request_id, subject_key, purpose, epoch,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?)",
                snapshotId, requestId, subjectKey, purpose.name(), epoch, Timestamp.from(createdAt));
    }

    List<SnapshotItemRow> findSnapshotItems(String snapshotId) {
        return jdbc.query(
                "SELECT snapshot_id, record_key, epoch, region_code, consent_version, payload, item_index"
                        + " FROM consent_snapshot_item WHERE snapshot_id = ? ORDER BY item_index",
                ITEM_MAPPER, snapshotId);
    }

    int countSnapshotItems(String snapshotId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consent_snapshot_item WHERE snapshot_id = ?",
                Integer.class, snapshotId);
        return count == null ? 0 : count;
    }

    void insertSnapshotItem(String snapshotId, String recordKey, int epoch, String regionCode,
                            int consentVersion, String payload, int itemIndex) {
        jdbc.update(
                "INSERT INTO consent_snapshot_item (snapshot_id, record_key, epoch, region_code,"
                        + " consent_version, payload, item_index) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshotId, recordKey, epoch, regionCode, consentVersion, payload, itemIndex);
    }
}
