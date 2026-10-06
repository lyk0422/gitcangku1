package com.example.starter.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 序号区间域持久化层：serial_batch / serial_interval / request_log；
 * 所有 SQL 参数化；区间按闭区间存储，不展开单件序号。
 */
@Repository
public class SerialBatchRepository {

    /**
     * serial_batch 表行记录：新类型批次的数量、根标记与乐观版本。
     */
    public record SerialBatchRow(long id, String batchKey, String productCode, long quantity,
                                 boolean root, int version, String createdAt) {
    }

    /**
     * serial_interval 表行记录：某批次的一个闭区间。
     */
    public record IntervalRow(long id, String batchKey, String productCode,
                              long intervalStart, long intervalEnd, String createdAt) {
    }

    /**
     * request_log 表行记录：全局 requestId 幂等快照。
     */
    public record RequestRow(String requestId, String fingerprint,
                             int responseStatus, String responseBody) {
    }

    private static final RowMapper<SerialBatchRow> SERIAL_BATCH_MAPPER = (rs, n) ->
            new SerialBatchRow(rs.getLong("id"), rs.getString("batch_key"),
                    rs.getString("product_code"), rs.getLong("quantity"),
                    rs.getBoolean("is_root"), rs.getInt("version"), rs.getString("created_at"));

    private static final RowMapper<IntervalRow> INTERVAL_MAPPER = (rs, n) -> new IntervalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getLong("interval_start"), rs.getLong("interval_end"), rs.getString("created_at"));

    private static final RowMapper<RequestRow> REQUEST_MAPPER = (rs, n) -> new RequestRow(
            rs.getString("request_id"), rs.getString("fingerprint"),
            rs.getInt("response_status"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public SerialBatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertSerialBatch(String batchKey, String productCode, long quantity,
                                  boolean root, int version, String createdAt) {
        jdbc.update("INSERT INTO serial_batch (batch_key, product_code, quantity, is_root,"
                        + " version, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                batchKey, productCode, quantity, root, version, createdAt);
    }

    public Optional<SerialBatchRow> findSerialBatch(String batchKey) {
        return jdbc.query("SELECT * FROM serial_batch WHERE batch_key = ?",
                        SERIAL_BATCH_MAPPER, batchKey)
                .stream().findFirst();
    }

    public void incrementVersion(String batchKey) {
        jdbc.update("UPDATE serial_batch SET version = version + 1 WHERE batch_key = ?", batchKey);
    }

    public void insertInterval(String batchKey, String productCode, long start, long end,
                               String createdAt) {
        jdbc.update("INSERT INTO serial_interval (batch_key, product_code, interval_start,"
                        + " interval_end, created_at) VALUES (?, ?, ?, ?, ?)",
                batchKey, productCode, start, end, createdAt);
    }

    /**
     * 某批次当前登记的区间集合，按起点升序；拆分后父批区间保留为冻结历史。
     */
    public List<IntervalRow> findIntervals(String batchKey) {
        return jdbc.query("SELECT * FROM serial_interval WHERE batch_key = ?"
                + " ORDER BY interval_start", INTERVAL_MAPPER, batchKey);
    }

    /**
     * 同产品号下与新区间相交的已登记根批区间（含已拆分、已召回的根批，根域永久冻结）。
     */
    public List<IntervalRow> findRootDomainIntersections(String productCode, long start, long end) {
        return jdbc.query("SELECT si.* FROM serial_interval si"
                        + " JOIN serial_batch sb ON si.batch_key = sb.batch_key"
                        + " WHERE sb.is_root = TRUE AND si.product_code = ?"
                        + " AND si.interval_start <= ? AND si.interval_end >= ?",
                INTERVAL_MAPPER, productCode, end, start);
    }

    /**
     * 同产品号下区间覆盖指定序号的全部批次业务键（沿血缘链每层至多一个）。
     */
    public List<String> findOwnerCandidates(String productCode, long serial) {
        return jdbc.queryForList("SELECT batch_key FROM serial_interval"
                        + " WHERE product_code = ? AND interval_start <= ? AND interval_end >= ?",
                String.class, productCode, serial, serial);
    }

    public Optional<RequestRow> findRequest(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?",
                        REQUEST_MAPPER, requestId)
                .stream().findFirst();
    }

    public void insertRequest(RequestRow row, String createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, fingerprint, response_status,"
                        + " response_body, created_at) VALUES (?, ?, ?, ?, ?)",
                row.requestId(), row.fingerprint(), row.responseStatus(), row.responseBody(),
                createdAt);
    }
}
