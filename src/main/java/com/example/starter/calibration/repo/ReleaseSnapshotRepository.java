package com.example.starter.calibration.repo;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.calibration.model.ReleaseSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 放行幂等快照持久化。request_id 主键约束保证同一 requestId 只有一个首次快照；
 * 快照与放行业务数据在同一事务内提交，事务回滚则快照不留痕。
 */
@Repository
public class ReleaseSnapshotRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RowMapper<ReleaseSnapshot> MAPPER = (rs, rowNum) ->
            new ReleaseSnapshot(
                    rs.getString("request_id"),
                    rs.getString("batch_id"),
                    rs.getString("released_by"),
                    rs.getBigDecimal("temperature"),
                    rs.getBigDecimal("humidity"),
                    rs.getString("coefficient_version_tag"),
                    JdbcTimes.fromDb(rs.getObject("released_at", LocalDateTime.class)),
                    readKeys(rs.getString("request_keys")),
                    JdbcTimes.fromDb(rs.getObject("created_at", LocalDateTime.class)));

    private final JdbcTemplate jdbc;

    public ReleaseSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入放行快照；request_id 冲突时由调用方按“重放/冲突”处理。
     */
    public void insert(ReleaseSnapshot snapshot) {
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO release_snapshot "
                            + "(request_id, batch_id, released_by, temperature, humidity, "
                            + "coefficient_version_tag, released_at, request_keys, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
            ps.setString(1, snapshot.requestId());
            ps.setString(2, snapshot.batchId());
            ps.setString(3, snapshot.releasedBy());
            ps.setBigDecimal(4, snapshot.temperature());
            ps.setBigDecimal(5, snapshot.humidity());
            ps.setString(6, snapshot.coefficientVersionTag());
            ps.setObject(7, JdbcTimes.toDb(snapshot.releasedAt()));
            ps.setString(8, writeKeys(snapshot.keys()));
            ps.setObject(9, JdbcTimes.toDb(snapshot.createdAt()));
            return ps;
        });
    }

    /**
     * 按 requestId 查询快照（不加锁）。
     */
    public Optional<ReleaseSnapshot> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM release_snapshot WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    private static String writeKeys(List<String> keys) {
        try {
            return JSON.writeValueAsString(keys);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("放行测量键序列化失败", ex);
        }
    }

    private static List<String> readKeys(String json) {
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("放行测量键反序列化失败", ex);
        }
    }
}
