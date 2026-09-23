package com.example.starter.consent.catalog;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.consent.catalog.dto.MappingResult;
import com.example.starter.consent.catalog.dto.MigrationEvidenceResponse;

/**
 * 用途目录、目录代次、查询代次令牌与迁移证据的持久化访问。
 *
 * <p>所有查询使用参数化 SQL；迁移激活通过对 catalog_purpose 行与旧用途授权/记录行
 * 加写锁（FOR UPDATE）串行化并发授权、写入、撤回与另一目录迁移。
 */
@Repository
public class CatalogRepository {

    private static final RowMapper<PurposeRow> PURPOSE_MAPPER = (rs, rowNum) -> new PurposeRow(
            rs.getString("code"),
            rs.getString("status"),
            rs.getString("scope_canonical"),
            rs.getInt("introduced_generation"),
            (Integer) rs.getObject("split_generation"));

    private static final RowMapper<GenerationRow> GENERATION_MAPPER = (rs, rowNum) -> new GenerationRow(
            rs.getInt("generation"),
            rs.getString("migration_key"),
            rs.getString("source_purpose"),
            toInstant(rs.getTimestamp("effective_from")),
            toInstant(rs.getTimestamp("effective_to")));

    private static final RowMapper<ReplacementRow> REPLACEMENT_MAPPER = (rs, rowNum) -> new ReplacementRow(
            rs.getString("parent_code"),
            rs.getString("child_code"),
            rs.getInt("child_generation"),
            rs.getString("scope_canonical"));

    private static final RowMapper<EvidenceItemRow> ITEM_MAPPER = (rs, rowNum) -> new EvidenceItemRow(
            rs.getInt("ordinal"),
            rs.getString("item_type"),
            rs.getString("subject_key"),
            rs.getString("old_purpose"),
            rs.getInt("old_epoch"),
            rs.getString("new_purpose"),
            (Integer) rs.getObject("new_epoch"),
            rs.getString("record_key"),
            rs.getString("attribute_value"),
            MappingResult.valueOf(rs.getString("mapping_result")));

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 用途目录行。
     *
     * @param code                 用途代码
     * @param status               ACTIVE / SPLIT
     * @param scopeCanonical       范围规范化签名，初始历史用途为空
     * @param introducedGeneration 引入代次
     * @param splitGeneration      被拆分别次，未拆分为空
     */
    public record PurposeRow(String code, String status, String scopeCanonical,
                             int introducedGeneration, Integer splitGeneration) {
        public boolean active() {
            return "ACTIVE".equals(status);
        }
    }

    /**
     * 目录代次行。
     */
    public record GenerationRow(int generation, String migrationKey, String sourcePurpose,
                                Instant effectiveFrom, Instant effectiveTo) {
    }

    /**
     * 用途替代关系行。
     */
    public record ReplacementRow(String parentCode, String childCode, int childGeneration, String scopeCanonical) {
    }

    /**
     * 证据明细持久化行。
     */
    public record EvidenceItemRow(int ordinal, String itemType, String subjectKey, String oldPurpose,
                                  int oldEpoch, String newPurpose, Integer newEpoch, String recordKey,
                                  String attributeValue, MappingResult mappingResult) {
    }

    public int currentGeneration() {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(generation), 1) FROM catalog_generation",
                Integer.class);
        return max == null ? 1 : max;
    }

    public Optional<GenerationRow> findGeneration(int generation) {
        return jdbc.query("SELECT generation, migration_key, source_purpose, effective_from, effective_to"
                        + " FROM catalog_generation WHERE generation = ?",
                GENERATION_MAPPER, generation).stream().findFirst();
    }

    public Optional<PurposeRow> findPurpose(String code) {
        return jdbc.query("SELECT code, status, scope_canonical, introduced_generation, split_generation"
                        + " FROM catalog_purpose WHERE code = ?",
                PURPOSE_MAPPER, code).stream().findFirst();
    }

    /**
     * 锁定用途目录行并返回；并发迁移与对该用途的授权/写入借此串行化。
     */
    public Optional<PurposeRow> lockPurpose(String code) {
        return jdbc.query("SELECT code, status, scope_canonical, introduced_generation, split_generation"
                        + " FROM catalog_purpose WHERE code = ? FOR UPDATE",
                PURPOSE_MAPPER, code).stream().findFirst();
    }

    public List<ReplacementRow> findReplacementsByParent(String parentCode) {
        return jdbc.query("SELECT parent_code, child_code, child_generation, scope_canonical"
                + " FROM purpose_replacement WHERE parent_code = ?", REPLACEMENT_MAPPER, parentCode);
    }

    /**
     * 插入新目录代次并回填自增代次；同事务内即可见。
     */
    int insertGeneration(String migrationKey, String sourcePurpose,
                         Instant effectiveFrom, Instant effectiveTo) {
        org.springframework.jdbc.support.KeyHolder keyHolder =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO catalog_generation"
                            + " (migration_key, source_purpose, effective_from, effective_to)"
                            + " VALUES (?, ?, ?, ?)",
                    new String[] {"generation"});
            ps.setString(1, migrationKey);
            ps.setString(2, sourcePurpose);
            ps.setTimestamp(3, Timestamp.from(effectiveFrom));
            ps.setTimestamp(4, Timestamp.from(effectiveTo));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("目录代次自增主键回填失败");
        }
        return key.intValue();
    }

    public void insertPurpose(String code, String scopeCanonical, int introducedGeneration) {
        jdbc.update("INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)"
                        + " VALUES (?, 'ACTIVE', ?, ?)",
                code, scopeCanonical, introducedGeneration);
    }

    public void markPurposeSplit(String code, String scopeCanonical, int splitGeneration) {
        int updated = jdbc.update("UPDATE catalog_purpose"
                        + " SET status = 'SPLIT', scope_canonical = ?, split_generation = ?,"
                        + " split_at = CURRENT_TIMESTAMP WHERE code = ? AND status = 'ACTIVE'",
                scopeCanonical, splitGeneration, code);
        if (updated == 0) {
            throw new IllegalStateException("用途行状态已变化，无法拆分: " + code);
        }
    }

    public void insertReplacement(String parentCode, String childCode, int childGeneration, String scopeCanonical) {
        jdbc.update("INSERT INTO purpose_replacement"
                        + " (parent_code, child_code, child_generation, scope_canonical) VALUES (?, ?, ?, ?)",
                parentCode, childCode, childGeneration, scopeCanonical);
    }

    public void insertQueryToken(String token, int catalogGeneration) {
        jdbc.update("INSERT INTO query_generation (token, catalog_generation) VALUES (?, ?)",
                token, catalogGeneration);
    }

    public Optional<Integer> findQueryGeneration(String token) {
        return jdbc.query("SELECT catalog_generation FROM query_generation WHERE token = ?",
                (rs, rowNum) -> rs.getInt("catalog_generation"), token).stream().findFirst();
    }

    public boolean evidenceExists(String migrationKey) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM migration_evidence WHERE migration_key = ?",
                Integer.class, migrationKey);
        return count != null && count > 0;
    }

    public void insertEvidence(String migrationKey, int catalogGeneration, String sourcePurpose,
                        String requestId, String detailJson) {
        jdbc.update("INSERT INTO migration_evidence"
                        + " (migration_key, catalog_generation, source_purpose, request_id, detail)"
                        + " VALUES (?, ?, ?, ?, ?)",
                migrationKey, catalogGeneration, sourcePurpose, requestId, detailJson);
    }

    /**
     * 写入一条稳定排序的证据明细。
     */
    public void insertEvidenceItem(String migrationKey, int ordinal, String itemType, String subjectKey,
                            String oldPurpose, int oldEpoch, String newPurpose, Integer newEpoch,
                            String recordKey, String attributeValue, MappingResult mappingResult) {
        jdbc.update("INSERT INTO migration_evidence_item"
                        + " (migration_key, ordinal, item_type, subject_key, old_purpose, old_epoch,"
                        + " new_purpose, new_epoch, record_key, attribute_value, mapping_result)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                migrationKey, ordinal, itemType, subjectKey, oldPurpose, oldEpoch,
                newPurpose, newEpoch, recordKey, attributeValue, mappingResult.name());
    }

    /**
     * 证据查询：明细按 ordinal 稳定排序，只读。
     */
    public Optional<MigrationEvidenceResponse> findEvidence(String migrationKey) {
        return jdbc.query("SELECT migration_key, catalog_generation, source_purpose, request_id, activated_at"
                        + " FROM migration_evidence WHERE migration_key = ?",
                (rs, rowNum) -> new Object[] {
                        rs.getString("migration_key"),
                        rs.getInt("catalog_generation"),
                        rs.getString("source_purpose"),
                        rs.getString("request_id"),
                        toInstant(rs.getTimestamp("activated_at"))}, migrationKey).stream().findFirst()
                .map(meta -> {
                    Object[] values = meta;
                    List<MigrationEvidenceResponse.EvidenceItem> items = jdbc.query(
                            "SELECT ordinal, item_type, subject_key, old_purpose, old_epoch, new_purpose,"
                                    + " new_epoch, record_key, attribute_value, mapping_result"
                                    + " FROM migration_evidence_item WHERE migration_key = ? ORDER BY ordinal",
                            ITEM_MAPPER, migrationKey).stream()
                            .map(row -> new MigrationEvidenceResponse.EvidenceItem(
                                    row.itemType(), row.subjectKey(), row.oldPurpose(), row.oldEpoch(),
                                    row.newPurpose(), row.newEpoch(), row.recordKey(),
                                    row.attributeValue(), row.mappingResult()))
                            .toList();
                    return new MigrationEvidenceResponse(
                            (String) values[0], (Integer) values[1], (String) values[2], (String) values[3],
                            (Instant) values[4], items);
                });
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
