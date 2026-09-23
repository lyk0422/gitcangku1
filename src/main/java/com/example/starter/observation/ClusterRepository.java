package com.example.starter.observation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 重复观测簇归并结果持久化：duplicate_cluster 表头、cluster_member 成员冻结快照、
 * cluster_field_source 字段级不可变证据；三类记录在归并事务内原子写入，永不更新。
 * 所有 SQL 使用参数化查询。
 */
@Repository
public class ClusterRepository {

    private final JdbcTemplate jdbcTemplate;

    public ClusterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入归并簇表头；clusterKey 主键或 canonicalRecordId 唯一键冲突时抛出重复键异常。
     */
    public void insertHeader(ClusterHeader header) {
        jdbcTemplate.update(
                "INSERT INTO duplicate_cluster (cluster_key, canonical_record_id, site_key, observation_type, "
                        + "observed_at_from, observed_at_to, member_count, request_id, operator, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                header.clusterKey(), header.canonicalRecordId(), header.siteKey(), header.observationType(),
                Timestamp.from(header.observedAtFrom()), Timestamp.from(header.observedAtTo()),
                header.memberCount(), header.requestId(), header.operator());
    }

    /**
     * 插入一条成员冻结快照。
     */
    public void insertMember(ClusterMemberRecord member) {
        jdbcTemplate.update(
                "INSERT INTO cluster_member (cluster_key, record_id, generation, site_key, observation_type, "
                        + "device_id, observed_at, location, reading, note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                member.clusterKey(), member.recordId(), member.generation(), member.siteKey(),
                member.observationType(), member.deviceId(), Timestamp.from(member.observedAt()),
                member.location(), member.reading(), member.note());
    }

    /**
     * 插入一条字段级不可变证据。
     */
    public void insertFieldSource(ClusterFieldSourceRecord source) {
        jdbcTemplate.update(
                "INSERT INTO cluster_field_source (cluster_key, field_name, source_record_id, "
                        + "source_generation, source_value) VALUES (?, ?, ?, ?, ?)",
                source.clusterKey(), source.fieldName(), source.sourceRecordId(),
                source.sourceGeneration(), source.sourceValue());
    }

    /**
     * 按全局唯一 clusterKey 查询归并簇表头；不存在时返回空。
     */
    public Optional<ClusterHeader> findHeader(String clusterKey) {
        return jdbcTemplate.query(
                        "SELECT cluster_key, canonical_record_id, site_key, observation_type, "
                                + "observed_at_from, observed_at_to, member_count, request_id, operator "
                                + "FROM duplicate_cluster WHERE cluster_key = ?",
                        HEADER_MAPPER, clusterKey)
                .stream().findFirst();
    }

    /**
     * 查询簇的全部成员冻结快照，按记录键排序。
     */
    public List<ClusterMemberRecord> findMembers(String clusterKey) {
        return jdbcTemplate.query(
                "SELECT cluster_key, record_id, generation, site_key, observation_type, device_id, "
                        + "observed_at, location, reading, note FROM cluster_member "
                        + "WHERE cluster_key = ? ORDER BY record_id ASC",
                MEMBER_MAPPER, clusterKey);
    }

    /**
     * 查询簇的全部字段级来源证据，按字段名排序。
     */
    public List<ClusterFieldSourceRecord> findFieldSources(String clusterKey) {
        return jdbcTemplate.query(
                "SELECT cluster_key, field_name, source_record_id, source_generation, source_value "
                        + "FROM cluster_field_source WHERE cluster_key = ? ORDER BY field_name ASC",
                SOURCE_MAPPER, clusterKey);
    }

    private static final RowMapper<ClusterHeader> HEADER_MAPPER = (rs, rowNum) -> new ClusterHeader(
            rs.getString("cluster_key"),
            rs.getString("canonical_record_id"),
            rs.getString("site_key"),
            rs.getString("observation_type"),
            rs.getTimestamp("observed_at_from").toInstant(),
            rs.getTimestamp("observed_at_to").toInstant(),
            rs.getInt("member_count"),
            rs.getString("request_id"),
            rs.getString("operator"),
            null);

    private static final RowMapper<ClusterMemberRecord> MEMBER_MAPPER = (rs, rowNum) -> new ClusterMemberRecord(
            rs.getString("cluster_key"),
            rs.getString("record_id"),
            rs.getInt("generation"),
            rs.getString("site_key"),
            rs.getString("observation_type"),
            rs.getString("device_id"),
            rs.getTimestamp("observed_at").toInstant(),
            rs.getString("location"),
            rs.getString("reading"),
            rs.getString("note"));

    private static final RowMapper<ClusterFieldSourceRecord> SOURCE_MAPPER = (rs, rowNum) ->
            new ClusterFieldSourceRecord(
                    rs.getString("cluster_key"),
                    rs.getString("field_name"),
                    rs.getString("source_record_id"),
                    rs.getInt("source_generation"),
                    rs.getString("source_value"));
}
