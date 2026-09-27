package com.example.starter.evidence;

import com.example.starter.evidence.dto.FrozenChainEventView;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 披露包逐件冻结快照表访问。快照仅在建包事务内批量插入，之后只读，
 * 不提供任何更新语句，迁移/复核/新增链记录无法改写已固化内容。
 */
@Repository
public class DisclosurePackageItemRepository {

    private static final TypeReference<List<FrozenChainEventView>> EVENTS_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DisclosurePackageItemRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入一条冻结快照。
     */
    public void insert(DisclosurePackageItem item) {
        jdbc.update("""
                        INSERT INTO disclosure_package_item
                            (package_key, evidence_key, sort_order, chain_version, chain_hash,
                             custodian_id, evidence_status, seal_no, seal_ok, latest_inspection_passed,
                             item_digest, snapshot_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                item.packageKey(), item.evidenceKey(), item.sortOrder(), item.chainVersion(),
                item.chainHash(), item.custodianId(), item.evidenceStatus().name(), item.sealNo(),
                item.sealOk() ? 1 : 0,
                item.latestInspectionPassed() == null ? null : (item.latestInspectionPassed() ? 1 : 0),
                item.itemDigest(), item.snapshotJson(), item.createdAt());
    }

    /**
     * 按规范化顺序查询包内全部冻结快照。
     */
    public List<DisclosurePackageItem> findByPackageKey(String packageKey) {
        return jdbc.query(
                "SELECT * FROM disclosure_package_item WHERE package_key = ? ORDER BY sort_order, id",
                new RowMapperWithMapper(objectMapper), packageKey);
    }

    /**
     * 反序列化快照中的链事件列表。
     */
    public List<FrozenChainEventView> readEvents(DisclosurePackageItem item) {
        try {
            return objectMapper.readValue(item.snapshotJson(), EVENTS_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("冻结快照反序列化失败: " + item.evidenceKey(), e);
        }
    }

    /**
     * 序列化链事件列表为快照 JSON。
     */
    public String writeEvents(List<FrozenChainEventView> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            throw new IllegalStateException("冻结快照序列化失败", e);
        }
    }

    private static final class RowMapperWithMapper implements RowMapper<DisclosurePackageItem> {
        private final ObjectMapper objectMapper;

        private RowMapperWithMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public DisclosurePackageItem mapRow(ResultSet rs, int rowNum) throws SQLException {
            Integer latestPassed = rs.getObject("latest_inspection_passed", Integer.class);
            return new DisclosurePackageItem(
                    rs.getLong("id"),
                    rs.getString("package_key"),
                    rs.getString("evidence_key"),
                    rs.getInt("sort_order"),
                    rs.getInt("chain_version"),
                    rs.getString("chain_hash"),
                    rs.getString("custodian_id"),
                    EvidenceStatus.valueOf(rs.getString("evidence_status")),
                    rs.getString("seal_no"),
                    rs.getInt("seal_ok") == 1,
                    latestPassed == null ? null : latestPassed == 1,
                    rs.getString("item_digest"),
                    rs.getString("snapshot_json"),
                    rs.getObject("created_at", LocalDateTime.class));
        }
    }
}
