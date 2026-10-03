package com.example.gitcangku1.repo;

import com.example.gitcangku1.domain.Errata;
import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.domain.ReleaseItem;
import com.example.gitcangku1.domain.ReleaseOrder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ReleaseRepository {

    private final JdbcTemplate jdbc;

    public ReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReleaseOrder> ORDER_MAPPER = (rs, n) -> new ReleaseOrder(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("target_version"),
            rs.getString("request_id"),
            rs.getString("request_hash"),
            rs.getObject("released_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private static final RowMapper<ReleaseItem> ITEM_MAPPER = (rs, n) -> new ReleaseItem(
            rs.getLong("id"),
            rs.getLong("release_id"),
            rs.getInt("roll_no"),
            MeasureType.valueOf(rs.getString("adopted_source")),
            rs.getDouble("l_value"),
            rs.getDouble("a_value"),
            rs.getDouble("b_value"),
            rs.getDouble("delta_e"),
            rs.getObject("measured_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC));

    private static final RowMapper<Errata> ERRATA_MAPPER = (rs, n) -> new Errata(
            rs.getLong("id"),
            rs.getLong("release_id"),
            rs.getString("note"),
            rs.getObject("created_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC));

    public ReleaseOrder insertOrder(long batchId, int targetVersion, String requestId,
            String requestHash, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO release_order(batch_id,target_version,request_id,request_hash,released_at)"
                            + " VALUES(?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, targetVersion);
            ps.setString(3, requestId);
            ps.setString(4, requestHash);
            ps.setObject(5, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        return jdbc.query("SELECT * FROM release_order WHERE id=?", ORDER_MAPPER, id)
                .stream().findFirst().orElseThrow();
    }

    public void insertItem(long releaseId, int rollNo, MeasureType source,
            double l, double a, double b, double deltaE, Instant measuredAt) {
        jdbc.update("INSERT INTO release_item(release_id,roll_no,adopted_source,l_value,a_value,b_value,"
                        + "delta_e,measured_at) VALUES(?,?,?,?,?,?,?,?)",
                releaseId, rollNo, source.name(), l, a, b, deltaE, LocalDateTime.ofInstant(measuredAt, ZoneOffset.UTC));
    }

    public Optional<ReleaseOrder> findOrderByBatch(long batchId) {
        return jdbc.query("SELECT * FROM release_order WHERE batch_id=?", ORDER_MAPPER, batchId)
                .stream().findFirst();
    }

    public Optional<ReleaseOrder> findOrderByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM release_order WHERE request_id=?", ORDER_MAPPER, requestId)
                .stream().findFirst();
    }

    public List<ReleaseItem> findItems(long releaseId) {
        return jdbc.query("SELECT * FROM release_item WHERE release_id=? ORDER BY roll_no",
                ITEM_MAPPER, releaseId);
    }

    public long countOrders(long batchId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM release_order WHERE batch_id=?",
                Long.class, batchId);
    }

    public Errata insertErrata(long releaseId, String note, Instant now) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO release_errata(release_id,note,created_at) VALUES(?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, releaseId);
            ps.setString(2, note);
            ps.setObject(3, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
            return ps;
        }, kh);
        long id = kh.getKey().longValue();
        return jdbc.query("SELECT * FROM release_errata WHERE id=?", ERRATA_MAPPER, id)
                .stream().findFirst().orElseThrow();
    }

    public List<Errata> findErrata(long releaseId) {
        return jdbc.query("SELECT * FROM release_errata WHERE release_id=? ORDER BY id",
                ERRATA_MAPPER, releaseId);
    }
}