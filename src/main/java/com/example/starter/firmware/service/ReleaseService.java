package com.example.starter.firmware.service;

import com.example.starter.firmware.dto.RatioUpdateRequest;
import com.example.starter.firmware.dto.ReleaseCreateRequest;
import com.example.starter.firmware.dto.ReleaseResponse;
import com.example.starter.firmware.dto.RequestIdOnlyRequest;
import com.example.starter.firmware.error.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 发布单管理：创建、扩量、取消与查询。
 * 同型号至多一张 ACTIVE 发布单，由 active_key 唯一索引在数据库层保证。
 */
@Service
public class ReleaseService {

    private static final RowMapper<ReleaseResponse> MAPPER = (rs, i) -> new ReleaseResponse(
            rs.getLong("id"), rs.getString("model"), rs.getString("from_version"),
            rs.getString("to_version"), rs.getInt("ratio"), rs.getInt("version"),
            rs.getString("status"));

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;

    public ReleaseService(JdbcTemplate jdbc, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
    }

    /**
     * 创建发布单，版本从 1 开始；目标版本必须与来源版本不同，同型号存在 ACTIVE 发布单时返回 409。
     */
    @Transactional
    public ReleaseResponse create(ReleaseCreateRequest req) {
        String hash = String.join("|", req.model(), req.fromVersion(), req.toVersion(),
                String.valueOf(req.ratio()));
        return idempotency.execute(req.requestId(), "RELEASE_CREATE", hash, ReleaseResponse.class, () -> {
            if (req.fromVersion().equals(req.toVersion())) {
                throw ApiException.badRequest("VERSION_NOT_DISTINCT",
                        "toVersion must differ from fromVersion");
            }
            KeyHolder keyHolder = new GeneratedKeyHolder();
            try {
                jdbc.update(con -> {
                    PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO release_order(model, from_version, to_version, ratio, version, status,"
                                    + " active_key, request_id) VALUES (?,?,?,?,1,'ACTIVE',?,?)",
                            new String[]{"id"});
                    ps.setString(1, req.model());
                    ps.setString(2, req.fromVersion());
                    ps.setString(3, req.toVersion());
                    ps.setInt(4, req.ratio());
                    ps.setString(5, req.model());
                    ps.setString(6, req.requestId());
                    return ps;
                }, keyHolder);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS",
                        "an ACTIVE release already exists for model: " + req.model());
            }
            long id = keyHolder.getKey().longValue();
            return new ReleaseResponse(id, req.model(), req.fromVersion(), req.toVersion(),
                    req.ratio(), 1, "ACTIVE");
        });
    }

    /**
     * 扩量：比例只增不减，expectedVersion 与当前版本不一致时返回 409，成功后版本加一。
     */
    @Transactional
    public ReleaseResponse updateRatio(long releaseId, RatioUpdateRequest req) {
        String hash = String.join("|", String.valueOf(releaseId), String.valueOf(req.ratio()),
                String.valueOf(req.expectedVersion()));
        return idempotency.execute(req.requestId(), "RELEASE_UPDATE_RATIO", hash, ReleaseResponse.class, () -> {
            ReleaseResponse current = lockById(releaseId);
            if (!"ACTIVE".equals(current.status())) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE", "release is not ACTIVE: " + releaseId);
            }
            if (current.version() != req.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expected version " + req.expectedVersion() + " but current is " + current.version());
            }
            if (req.ratio() <= current.ratio()) {
                throw ApiException.conflict("RATIO_NOT_INCREASING",
                        "ratio must be greater than current ratio " + current.ratio());
            }
            jdbc.update("UPDATE release_order SET ratio=?, version=version+1, updated_at=? WHERE id=?",
                    req.ratio(), Timestamp.from(Instant.now()), releaseId);
            return new ReleaseResponse(releaseId, current.model(), current.fromVersion(),
                    current.toVersion(), req.ratio(), current.version() + 1, current.status());
        });
    }

    /**
     * 取消发布单：尚未终结的任务变为 CANCELLED；已终结（SUCCESS/FAILED）任务不受影响。
     * 重复取消按成功处理，返回当前状态。
     */
    @Transactional
    public ReleaseResponse cancel(long releaseId, RequestIdOnlyRequest req) {
        String hash = String.valueOf(releaseId);
        return idempotency.execute(req.requestId(), "RELEASE_CANCEL", hash, ReleaseResponse.class, () -> {
            ReleaseResponse current = lockById(releaseId);
            if ("CANCELLED".equals(current.status())) {
                return current;
            }
            jdbc.update("UPDATE release_order SET status='CANCELLED', active_key=NULL, updated_at=? WHERE id=?",
                    Timestamp.from(Instant.now()), releaseId);
            jdbc.update("UPDATE rollout_task SET status='CANCELLED', updated_at=?"
                            + " WHERE release_id=? AND status='PENDING'",
                    Timestamp.from(Instant.now()), releaseId);
            return new ReleaseResponse(current.id(), current.model(), current.fromVersion(),
                    current.toVersion(), current.ratio(), current.version(), "CANCELLED");
        });
    }

    /**
     * 查询发布单；不存在时返回 404。
     */
    @Transactional(readOnly = true)
    public ReleaseResponse get(long releaseId) {
        List<ReleaseResponse> rows = jdbc.query(
                "SELECT * FROM release_order WHERE id=?", MAPPER, releaseId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("RELEASE_NOT_FOUND", "release not found: " + releaseId);
        }
        return rows.get(0);
    }

    /**
     * 按主键锁定发布单行（FOR UPDATE），用于写操作间串行化；不存在时返回 404。
     */
    private ReleaseResponse lockById(long releaseId) {
        List<ReleaseResponse> rows = jdbc.query(
                "SELECT * FROM release_order WHERE id=? FOR UPDATE", MAPPER, releaseId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("RELEASE_NOT_FOUND", "release not found: " + releaseId);
        }
        return rows.get(0);
    }
}
