package com.example.starter.batch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 序号区间域与全局请求幂等持久化层：所有 SQL 参数化；时间以 ISO-8601 UTC 字符串存取。
 * 区间只按端点存储与检索，不展开为逐件序号记录。
 */
@Repository
public class SerialRepository {

    /**
     * batch_serial_interval 表行记录。
     */
    public record IntervalRow(long id, String batchKey, String productCode, String kind,
                              int startSerial, int endSerial, int seq, String createdAt) {
    }

    /**
     * request_log 表行记录：全局请求幂等快照。
     */
    public record RequestLogRow(String requestId, String operation, String resource,
                                String fingerprint, int responseStatus, String responseBody) {
    }

    /**
     * 序号归属查询结果：当前持有序号的叶批业务键及其状态。
     */
    public record LeafOwnerRow(String batchKey, String status) {
    }

    private static final RowMapper<IntervalRow> INTERVAL_MAPPER = (rs, n) -> new IntervalRow(
            rs.getLong("id"), rs.getString("batch_key"), rs.getString("product_code"),
            rs.getString("kind"), rs.getInt("start_serial"), rs.getInt("end_serial"),
            rs.getInt("seq"), rs.getString("created_at"));

    private static final RowMapper<RequestLogRow> REQUEST_MAPPER = (rs, n) -> new RequestLogRow(
            rs.getString("request_id"), rs.getString("operation"), rs.getString("resource"),
            rs.getString("fingerprint"), rs.getInt("response_status"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public SerialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertInterval(String batchKey, String productCode, String kind,
                               int startSerial, int endSerial, int seq, String createdAt) {
        jdbc.update("INSERT INTO batch_serial_interval (batch_key, product_code, kind,"
                        + " start_serial, end_serial, seq, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                batchKey, productCode, kind, startSerial, endSerial, seq, createdAt);
    }

    /**
     * 某批次的序号域区间，按 seq 升序（即起始序号升序）。
     */
    public List<IntervalRow> findIntervals(String batchKey) {
        return jdbc.query("SELECT * FROM batch_serial_interval WHERE batch_key = ? ORDER BY seq",
                INTERVAL_MAPPER, batchKey);
    }

    /**
     * 某产品全部根批登记（ROOT）区间；根批拆分或召回后仍保留，用于域相交判定。
     */
    public List<IntervalRow> findRootIntervalsByProduct(String productCode) {
        return jdbc.query("SELECT * FROM batch_serial_interval"
                        + " WHERE product_code = ? AND kind = 'ROOT' ORDER BY id",
                INTERVAL_MAPPER, productCode);
    }

    /**
     * 查询当前持有某序号的叶批：区间覆盖该序号且批次无血缘子批
     * （已拆分父批的区间仅作历史；父批拆分后再被召回状态变为 RECALLED，不能仅凭状态排除）。
     */
    public Optional<LeafOwnerRow> findLeafOwner(String productCode, int serial) {
        return jdbc.query("SELECT i.batch_key, b.status FROM batch_serial_interval i"
                        + " JOIN batch b ON b.batch_key = i.batch_key"
                        + " WHERE i.product_code = ? AND i.start_serial <= ? AND i.end_serial >= ?"
                        + " AND NOT EXISTS (SELECT 1 FROM batch_lineage l"
                        + " WHERE l.parent_key = i.batch_key)",
                (rs, n) -> new LeafOwnerRow(rs.getString("batch_key"), rs.getString("status")),
                productCode, serial, serial).stream().findFirst();
    }

    public Optional<RequestLogRow> findRequest(String requestId) {
        return jdbc.query("SELECT * FROM request_log WHERE request_id = ?",
                REQUEST_MAPPER, requestId).stream().findFirst();
    }

    public void insertRequest(RequestLogRow row, String createdAt) {
        jdbc.update("INSERT INTO request_log (request_id, operation, resource, fingerprint,"
                        + " response_status, response_body, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.operation(), row.resource(), row.fingerprint(),
                row.responseStatus(), row.responseBody(), createdAt);
    }

    /**
     * 按产品编码取登记互斥锁：首次插入锁行（并发重复插入吞掉唯一键冲突），
     * 随后 SELECT ... FOR UPDATE 持有行锁，串行化同一产品的根批域相交判定。
     */
    public void lockProductDomain(String productCode) {
        try {
            jdbc.update("INSERT INTO serial_domain_lock (product_code) VALUES (?)", productCode);
        } catch (DuplicateKeyException e) {
            // 锁行已存在：忽略，转入下方行锁等待
        }
        jdbc.queryForList("SELECT product_code FROM serial_domain_lock WHERE product_code = ?"
                + " FOR UPDATE", String.class, productCode);
    }
}
