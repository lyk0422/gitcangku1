package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 重复观测簇持久化：duplicate_cluster / cluster_member / cluster_field_source 三表。
 * 全部记录在归并确认事务内原子写入，成功后不可变、永不更新或删除。所有 SQL 使用参数化查询。
 */
@Repository
public class ClusterRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClusterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<DuplicateCluster> CLUSTER_MAPPER = (rs, rowNum) -> new DuplicateCluster(
            rs.getString("cluster_key"),
            rs.getString("canonical_record_key"),
            rs.getString("site_key"),
            rs.getString("obs_type"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("member_count"),
            rs.getString("request_id"));

    private static final RowMapper<ClusterMemberEvidence> MEMBER_MAPPER = (rs, rowNum) ->
            new ClusterMemberEvidence(
                    rs.getString("cluster_key"),
                    rs.getString("record_key"),
                    rs.getInt("generation"),
                    rs.getString("device_id"),
                    rs.getTimestamp("observed_at").toInstant(),
                    rs.getString("field_location"),
                    rs.getString("field_reading"),
                    rs.getString("field_note"),
                    rs.getInt("ordinal"));

    private static final RowMapper<ClusterFieldSource> SOURCE_MAPPER = (rs, rowNum) ->
            new ClusterFieldSource(
                    rs.getString("cluster_key"),
                    rs.getString("field_name"),
                    rs.getString("source_record_key"),
                    rs.getInt("source_generation"),
                    rs.getString("source_value"));

    /**
     * 插入簇主记录；clusterKey 主键冲突时抛 DuplicateKeyException。
     */
    public void insertCluster(DuplicateCluster cluster) {
        jdbcTemplate.update(
                "INSERT INTO duplicate_cluster (cluster_key, canonical_record_key, site_key, obs_type, "
                        + "window_start, window_end, member_count, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                cluster.clusterKey(), cluster.canonicalRecordKey(), cluster.siteKey(), cluster.obsType(),
                Timestamp.from(cluster.windowStart()), Timestamp.from(cluster.windowEnd()),
                cluster.memberCount(), cluster.requestId());
    }

    /**
     * 插入一条成员冻结证据。
     */
    public void insertMember(ClusterMemberEvidence member) {
        jdbcTemplate.update(
                "INSERT INTO cluster_member (cluster_key, record_key, generation, device_id, observed_at, "
                        + "field_location, field_reading, field_note, ordinal) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                member.clusterKey(), member.recordKey(), member.generation(), member.deviceId(),
                Timestamp.from(member.observedAt()), member.location(), member.reading(),
                member.note(), member.ordinal());
    }

    /**
     * 插入一条字段级溯源证据。
     */
    public void insertFieldSource(ClusterFieldSource source) {
        jdbcTemplate.update(
                "INSERT INTO cluster_field_source (cluster_key, field_name, source_record_key, "
                        + "source_generation, source_value) VALUES (?, ?, ?, ?, ?)",
                source.clusterKey(), source.fieldName(), source.sourceRecordKey(),
                source.sourceGeneration(), source.sourceValue());
    }

    /**
     * 按 clusterKey 查询簇主记录；不存在返回空。
     */
    public Optional<DuplicateCluster> findByClusterKey(String clusterKey) {
        return jdbcTemplate.query(
                        "SELECT cluster_key, canonical_record_key, site_key, obs_type, window_start, "
                                + "window_end, member_count, request_id FROM duplicate_cluster WHERE cluster_key = ?",
                        CLUSTER_MAPPER, clusterKey)
                .stream().findFirst();
    }

    /**
     * 按 canonical 主记录键查询簇；不存在返回空（供由主记录反查簇）。
     */
    public Optional<DuplicateCluster> findByCanonicalKey(String canonicalRecordKey) {
        return jdbcTemplate.query(
                        "SELECT cluster_key, canonical_record_key, site_key, obs_type, window_start, "
                                + "window_end, member_count, request_id FROM duplicate_cluster "
                                + "WHERE canonical_record_key = ?",
                        CLUSTER_MAPPER, canonicalRecordKey)
                .stream().findFirst();
    }

    /**
     * 查询簇的全部成员冻结证据，按审核人提交序号升序。
     */
    public List<ClusterMemberEvidence> findMembers(String clusterKey) {
        return jdbcTemplate.query(
                "SELECT cluster_key, record_key, generation, device_id, observed_at, field_location, "
                        + "field_reading, field_note, ordinal FROM cluster_member "
                        + "WHERE cluster_key = ? ORDER BY ordinal ASC, record_key ASC",
                MEMBER_MAPPER, clusterKey);
    }

    /**
     * 查询簇的全部字段级溯源证据，按字段名升序。
     */
    public List<ClusterFieldSource> findFieldSources(String clusterKey) {
        return jdbcTemplate.query(
                "SELECT cluster_key, field_name, source_record_key, source_generation, source_value "
                        + "FROM cluster_field_source WHERE cluster_key = ? ORDER BY field_name ASC",
                SOURCE_MAPPER, clusterKey);
    }

    /**
     * 判断某观测记录键是否已作为某簇的 canonical 主记录存在；用于新主记录键冲突与归并链环检测。
     */
    public boolean existsClusterByCanonicalKey(String canonicalRecordKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM duplicate_cluster WHERE canonical_record_key = ?",
                Integer.class, canonicalRecordKey);
        return count != null && count > 0;
    }

    /**
     * 判断某观测记录是否已是某簇成员（已归并）；用于链环检测，防止把 MERGED 记录当 canonical 再归并。
     */
    public boolean existsMembership(String recordKey) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cluster_member WHERE record_key = ?",
                Integer.class, recordKey);
        return count != null && count > 0;
    }
}
