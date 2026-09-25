package com.example.starter.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 授权代理委托、批量查询快照与阻断审计的持久化访问，基于 JdbcTemplate，全部使用参数化 SQL。
 */
@Repository
public class DelegateRepository {

    private static final RowMapper<DelegateRow> DELEGATE_MAPPER = (rs, rowNum) -> new DelegateRow(
            rs.getString("delegate_key"),
            rs.getString("subject_key"),
            rs.getString("delegate_id"),
            parsePurposes(rs.getString("purposes")),
            parseEpochs(rs.getString("epochs")),
            Instant.parse(rs.getString("valid_from")),
            Instant.parse(rs.getString("valid_to")),
            rs.getInt("delegate_version"),
            DelegateStatus.valueOf(rs.getString("status")));

    private static final RowMapper<QueryRow> QUERY_MAPPER = (rs, rowNum) -> new QueryRow(
            rs.getString("query_id"),
            rs.getString("delegate_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("params_fingerprint"));

    private static final RowMapper<QueryItemRow> QUERY_ITEM_MAPPER = (rs, rowNum) -> new QueryItemRow(
            rs.getString("query_id"),
            rs.getString("subject_key"),
            rs.getInt("epoch"),
            rs.getString("delegate_key"),
            rs.getInt("delegate_version"),
            rs.getString("record_key"),
            rs.getString("payload"));

    private static final RowMapper<BlockRow> BLOCK_MAPPER = (rs, rowNum) -> new BlockRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("delegate_id"),
            Purpose.valueOf(rs.getString("purpose")),
            rs.getString("subject_keys"),
            rs.getString("reasons"));

    private static final String DELEGATE_COLUMNS =
            "delegate_key, subject_key, delegate_id, purposes, epochs, valid_from, valid_to, delegate_version, status";

    private final JdbcTemplate jdbc;

    public DelegateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 委托行。
     *
     * @param delegateKey     委托指纹
     * @param subjectKey      主体标识
     * @param delegateId      代理人标识
     * @param purposes        规范化用途集合（升序）
     * @param epochs          创建时各用途授权代次
     * @param validFrom       UTC 有效期起点（含）
     * @param validTo         UTC 有效期终点（不含）
     * @param delegateVersion 委托版本
     * @param status          状态：ACTIVE 有效 / REVOKED 已撤销
     */
    public record DelegateRow(String delegateKey, String subjectKey, String delegateId,
                              List<Purpose> purposes, Map<Purpose, Integer> epochs,
                              Instant validFrom, Instant validTo, int delegateVersion,
                              DelegateStatus status) {
    }

    /**
     * 批量查询快照头行。
     *
     * @param queryId           批量查询标识
     * @param delegateId        代理人标识
     * @param purpose           查询用途
     * @param paramsFingerprint 规范化参数指纹
     */
    public record QueryRow(String queryId, String delegateId, Purpose purpose, String paramsFingerprint) {
    }

    /**
     * 批量查询快照明细行：固化授权代次、委托指纹与版本及当时记录。
     */
    public record QueryItemRow(String queryId, String subjectKey, int epoch, String delegateKey,
                               int delegateVersion, String recordKey, String payload) {
    }

    /**
     * 阻断审计行。
     *
     * @param id          自增标识
     * @param requestId   失败请求标识（不占用幂等键）
     * @param delegateId  代理人标识
     * @param purpose     查询用途
     * @param subjectKeys 请求主体集合（逗号分隔）
     * @param reasonsJson 逐主体阻断原因（JSON）
     */
    public record BlockRow(long id, String requestId, String delegateId, Purpose purpose,
                           String subjectKeys, String reasonsJson) {
    }

    static String formatPurposes(List<Purpose> purposes) {
        StringBuilder sb = new StringBuilder();
        for (Purpose purpose : purposes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(purpose.name());
        }
        return sb.toString();
    }

    static String formatEpochs(Map<Purpose, Integer> epochs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Purpose, Integer> entry : epochs.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey().name()).append(':').append(entry.getValue());
        }
        return sb.toString();
    }

    static List<Purpose> parsePurposes(String stored) {
        List<Purpose> purposes = new ArrayList<>();
        for (String token : stored.split(",")) {
            purposes.add(Purpose.valueOf(token));
        }
        return purposes;
    }

    static Map<Purpose, Integer> parseEpochs(String stored) {
        Map<Purpose, Integer> epochs = new LinkedHashMap<>();
        for (String token : stored.split(",")) {
            String[] parts = token.split(":");
            epochs.put(Purpose.valueOf(parts[0]), Integer.valueOf(parts[1]));
        }
        return epochs;
    }

    Optional<DelegateRow> findByKey(String delegateKey) {
        List<DelegateRow> rows = jdbc.query(
                "SELECT " + DELEGATE_COLUMNS + " FROM delegate_grant WHERE delegate_key = ?",
                DELEGATE_MAPPER, delegateKey);
        return rows.stream().findFirst();
    }

    List<DelegateRow> findBySubjectAndDelegate(String subjectKey, String delegateId) {
        return jdbc.query(
                "SELECT " + DELEGATE_COLUMNS + " FROM delegate_grant"
                        + " WHERE subject_key = ? AND delegate_id = ? ORDER BY created_at, delegate_key",
                DELEGATE_MAPPER, subjectKey, delegateId);
    }

    List<DelegateRow> findHistory(String subjectKey, String delegateId) {
        StringBuilder sql = new StringBuilder(
                "SELECT " + DELEGATE_COLUMNS + " FROM delegate_grant WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (subjectKey != null) {
            sql.append(" AND subject_key = ?");
            args.add(subjectKey);
        }
        if (delegateId != null) {
            sql.append(" AND delegate_id = ?");
            args.add(delegateId);
        }
        sql.append(" ORDER BY created_at, delegate_key");
        return jdbc.query(sql.toString(), DELEGATE_MAPPER, args.toArray());
    }

    void insert(DelegateRow row) {
        jdbc.update(
                "INSERT INTO delegate_grant (delegate_key, subject_key, delegate_id, purposes, epochs,"
                        + " valid_from, valid_to, delegate_version, status)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.delegateKey(), row.subjectKey(), row.delegateId(),
                formatPurposes(row.purposes()), formatEpochs(row.epochs()),
                row.validFrom().toString(), row.validTo().toString(),
                row.delegateVersion(), row.status().name());
    }

    /**
     * 仅当委托当前为 ACTIVE 时撤销；返回是否实际发生状态变更。
     */
    boolean revoke(String delegateKey) {
        int updated = jdbc.update(
                "UPDATE delegate_grant SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP"
                        + " WHERE delegate_key = ? AND status = 'ACTIVE'",
                delegateKey);
        return updated > 0;
    }

    Optional<QueryRow> findQuery(String queryId) {
        List<QueryRow> rows = jdbc.query(
                "SELECT query_id, delegate_id, purpose, params_fingerprint FROM delegate_query"
                        + " WHERE query_id = ?",
                QUERY_MAPPER, queryId);
        return rows.stream().findFirst();
    }

    void insertQuery(QueryRow row) {
        jdbc.update(
                "INSERT INTO delegate_query (query_id, delegate_id, purpose, params_fingerprint)"
                        + " VALUES (?, ?, ?, ?)",
                row.queryId(), row.delegateId(), row.purpose().name(), row.paramsFingerprint());
    }

    void insertQueryItem(QueryItemRow row) {
        jdbc.update(
                "INSERT INTO delegate_query_item"
                        + " (query_id, subject_key, epoch, delegate_key, delegate_version, record_key, payload)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.queryId(), row.subjectKey(), row.epoch(), row.delegateKey(),
                row.delegateVersion(), row.recordKey(), row.payload());
    }

    List<QueryItemRow> findQueryItems(String queryId) {
        return jdbc.query(
                "SELECT query_id, subject_key, epoch, delegate_key, delegate_version, record_key, payload"
                        + " FROM delegate_query_item WHERE query_id = ?"
                        + " ORDER BY subject_key, record_key",
                QUERY_ITEM_MAPPER, queryId);
    }

    void insertBlock(String requestId, String delegateId, Purpose purpose,
                     String subjectKeys, String reasonsJson) {
        jdbc.update(
                "INSERT INTO delegate_query_block (request_id, delegate_id, purpose, subject_keys, reasons)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, delegateId, purpose.name(), subjectKeys, reasonsJson);
    }

    List<BlockRow> findBlocks(String delegateId) {
        if (delegateId == null) {
            return jdbc.query(
                    "SELECT id, request_id, delegate_id, purpose, subject_keys, reasons"
                            + " FROM delegate_query_block ORDER BY id",
                    BLOCK_MAPPER);
        }
        return jdbc.query(
                "SELECT id, request_id, delegate_id, purpose, subject_keys, reasons"
                        + " FROM delegate_query_block WHERE delegate_id = ? ORDER BY id",
                BLOCK_MAPPER, delegateId);
    }
}
