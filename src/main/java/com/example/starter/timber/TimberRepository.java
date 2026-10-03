package com.example.starter.timber;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 监管链数据访问：来源、批次树、移交单与幂等请求键的JDBC读写。
 */
@Repository
public class TimberRepository {

    private static final RowMapper<TimberSource> SOURCE_MAPPER = (rs, rowNum) -> new TimberSource(
            rs.getLong("id"), rs.getString("voucher_no"), rs.getString("species"),
            rs.getInt("weight_kg"), rs.getString("holder"));

    private static final RowMapper<TimberBatch> BATCH_MAPPER = (rs, rowNum) -> new TimberBatch(
            rs.getLong("id"), rs.getString("batch_no"), rs.getLong("source_id"),
            rs.getObject("parent_id", Long.class), rs.getString("voucher_no"), rs.getString("species"),
            rs.getInt("weight_kg"), rs.getString("holder"), rs.getBoolean("leaf"));

    private static final RowMapper<TransferOrder> TRANSFER_MAPPER = (rs, rowNum) -> new TransferOrder(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("from_holder"),
            rs.getString("to_holder"), rs.getString("status"), rs.getString("reject_reason"),
            rs.getString("request_id"));

    private static final RowMapper<RequestLogRow> REQUEST_MAPPER = (rs, rowNum) -> new RequestLogRow(
            rs.getString("request_id"), rs.getString("operation"),
            rs.getString("fingerprint"), rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    public TimberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TimberSource insertSource(String voucherNo, String species, int weightKg, String holder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO timber_source (voucher_no, species, weight_kg, holder) VALUES (?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setString(1, voucherNo);
            ps.setString(2, species);
            ps.setInt(3, weightKg);
            ps.setString(4, holder);
            return ps;
        }, keys);
        return new TimberSource(generatedId(keys), voucherNo, species, weightKg, holder);
    }

    public Optional<TimberSource> findSourceByVoucher(String voucherNo) {
        return queryOne("SELECT * FROM timber_source WHERE voucher_no = ?", SOURCE_MAPPER, voucherNo);
    }

    public TimberBatch insertBatch(String batchNo, long sourceId, Long parentId,
                                   String voucherNo, String species, int weightKg, String holder) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO timber_batch (batch_no, source_id, parent_id, voucher_no, species, weight_kg, holder, leaf)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)",
                    new String[] {"id"});
            ps.setString(1, batchNo);
            ps.setLong(2, sourceId);
            if (parentId == null) {
                ps.setObject(3, null);
            } else {
                ps.setLong(3, parentId);
            }
            ps.setString(4, voucherNo);
            ps.setString(5, species);
            ps.setInt(6, weightKg);
            ps.setString(7, holder);
            return ps;
        }, keys);
        return new TimberBatch(generatedId(keys), batchNo, sourceId, parentId, voucherNo, species,
                weightKg, holder, true);
    }

    public Optional<TimberBatch> findBatchByNo(String batchNo) {
        return queryOne("SELECT * FROM timber_batch WHERE batch_no = ?", BATCH_MAPPER, batchNo);
    }

    public Optional<TimberBatch> findBatchById(long id) {
        return queryOne("SELECT * FROM timber_batch WHERE id = ?", BATCH_MAPPER, id);
    }

    /** 按批次编号加行锁读取（SELECT ... FOR UPDATE），用于拆分与移交互斥。 */
    public Optional<TimberBatch> lockBatchByNo(String batchNo) {
        return queryOne("SELECT * FROM timber_batch WHERE batch_no = ? FOR UPDATE", BATCH_MAPPER, batchNo);
    }

    public List<TimberBatch> findChildren(long parentId) {
        return jdbc.query("SELECT * FROM timber_batch WHERE parent_id = ? ORDER BY id", BATCH_MAPPER, parentId);
    }

    public void markNonLeaf(long batchId) {
        jdbc.update("UPDATE timber_batch SET leaf = FALSE WHERE id = ?", batchId);
    }

    public void updateBatchHolder(long batchId, String holder) {
        jdbc.update("UPDATE timber_batch SET holder = ? WHERE id = ?", holder, batchId);
    }

    public TransferOrder insertTransfer(long batchId, String fromHolder, String toHolder, String requestId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO transfer_order (batch_id, from_holder, to_holder, status, request_id)"
                            + " VALUES (?, ?, ?, 'PENDING', ?)",
                    new String[] {"id"});
            ps.setLong(1, batchId);
            ps.setString(2, fromHolder);
            ps.setString(3, toHolder);
            ps.setString(4, requestId);
            return ps;
        }, keys);
        return new TransferOrder(generatedId(keys), batchId, fromHolder, toHolder,
                TransferOrder.PENDING, null, requestId);
    }

    public Optional<TransferOrder> findTransferById(long id) {
        return queryOne("SELECT * FROM transfer_order WHERE id = ?", TRANSFER_MAPPER, id);
    }

    /** 按主键加行锁读取移交单，用于签收串行化。 */
    public Optional<TransferOrder> lockTransferById(long id) {
        return queryOne("SELECT * FROM transfer_order WHERE id = ? FOR UPDATE", TRANSFER_MAPPER, id);
    }

    public Optional<TransferOrder> findPendingTransferForBatch(long batchId) {
        return queryOne("SELECT * FROM transfer_order WHERE batch_id = ? AND status = 'PENDING'",
                TRANSFER_MAPPER, batchId);
    }

    public void decideTransfer(long transferId, String status, String rejectReason) {
        jdbc.update("UPDATE transfer_order SET status = ?, reject_reason = ?, decided_at = CURRENT_TIMESTAMP"
                + " WHERE id = ?", status, rejectReason, transferId);
    }

    public Optional<RequestLogRow> findRequest(String requestId) {
        return queryOne("SELECT * FROM request_log WHERE request_id = ?", REQUEST_MAPPER, requestId);
    }

    public void insertRequest(String requestId, String operation, String fingerprint) {
        jdbc.update("INSERT INTO request_log (request_id, operation, fingerprint) VALUES (?, ?, ?)",
                requestId, operation, fingerprint);
    }

    public void completeRequest(String requestId, String responseBody) {
        jdbc.update("UPDATE request_log SET response_body = ? WHERE request_id = ?", responseBody, requestId);
    }

    private <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, mapper, args));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static long generatedId(KeyHolder keys) {
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("未返回自增主键");
        }
        return key.longValue();
    }
}
