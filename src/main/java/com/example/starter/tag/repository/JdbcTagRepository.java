package com.example.starter.tag.repository;

import com.example.starter.tag.domain.Attachment;
import com.example.starter.tag.domain.Handoff;
import com.example.starter.tag.domain.HandoffStatus;
import com.example.starter.tag.domain.IdempotentRequest;
import com.example.starter.tag.domain.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的标签业务仓储，全部 SQL 使用参数绑定。
 */
@Repository
public class JdbcTagRepository implements TagRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcTagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Tag> TAG_MAPPER = (rs, rowNum) -> {
        Tag tag = new Tag();
        tag.setId(rs.getLong("id"));
        tag.setTagCode(rs.getString("tag_code"));
        tag.setHolderSiteId(rs.getLong("holder_site_id"));
        Timestamp registeredAt = rs.getTimestamp("registered_at");
        tag.setRegisteredAt(registeredAt == null ? null : registeredAt.toInstant());
        return tag;
    };

    private static final RowMapper<Attachment> ATTACHMENT_MAPPER = (rs, rowNum) -> {
        Attachment attachment = new Attachment();
        attachment.setId(rs.getLong("id"));
        attachment.setAnimalId(rs.getLong("animal_id"));
        attachment.setTagId(rs.getLong("tag_id"));
        attachment.setSiteId(rs.getLong("site_id"));
        Timestamp attachAt = rs.getTimestamp("attach_at");
        attachment.setAttachAt(attachAt == null ? null : attachAt.toInstant());
        Timestamp detachAt = rs.getTimestamp("detach_at");
        attachment.setDetachAt(detachAt == null ? null : detachAt.toInstant());
        long detachSiteId = rs.getLong("detach_site_id");
        attachment.setDetachSiteId(rs.wasNull() ? null : detachSiteId);
        return attachment;
    };

    private static final RowMapper<Handoff> HANDOFF_MAPPER = (rs, rowNum) -> {
        Handoff handoff = new Handoff();
        handoff.setId(rs.getLong("id"));
        handoff.setHandoffCode(rs.getString("handoff_code"));
        handoff.setTagId(rs.getLong("tag_id"));
        handoff.setFromSiteId(rs.getLong("from_site_id"));
        handoff.setToSiteId(rs.getLong("to_site_id"));
        handoff.setStatus(HandoffStatus.valueOf(rs.getString("status")));
        Timestamp initiatedAt = rs.getTimestamp("initiated_at");
        handoff.setInitiatedAt(initiatedAt == null ? null : initiatedAt.toInstant());
        Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
        handoff.setConfirmedAt(confirmedAt == null ? null : confirmedAt.toInstant());
        Timestamp closedAt = rs.getTimestamp("closed_at");
        handoff.setClosedAt(closedAt == null ? null : closedAt.toInstant());
        handoff.setRejectReason(rs.getString("reject_reason"));
        return handoff;
    };

    private static final RowMapper<IdempotentRequest> IDEMPOTENT_MAPPER = (rs, rowNum) -> {
        IdempotentRequest request = new IdempotentRequest();
        request.setId(rs.getLong("id"));
        request.setRequestId(rs.getString("request_id"));
        request.setRequestType(rs.getString("request_type"));
        request.setRequestHash(rs.getString("request_hash"));
        request.setResultId(rs.getLong("result_id"));
        Timestamp createdAt = rs.getTimestamp("created_at");
        request.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return request;
    };

    @Override
    public Optional<Tag> findTagByCode(String tagCode) {
        List<Tag> tags = jdbcTemplate.query(
                "SELECT id, tag_code, holder_site_id, registered_at FROM tag WHERE tag_code = ?",
                TAG_MAPPER, tagCode);
        return tags.stream().findFirst();
    }

    @Override
    public Tag findTagById(long tagId) {
        List<Tag> tags = jdbcTemplate.query(
                "SELECT id, tag_code, holder_site_id, registered_at FROM tag WHERE id = ?",
                TAG_MAPPER, tagId);
        return tags.stream().findFirst().orElse(null);
    }

    @Override
    public Tag getTagByCode(String tagCode) {
        return findTagByCode(tagCode).orElse(null);
    }

    @Override
    public Long findSiteIdByCode(String siteCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM site WHERE site_code = ?", Long.class, siteCode);
        return ids.stream().findFirst().orElse(null);
    }

    @Override
    public long requireSiteId(String siteCode) {
        Long id = findSiteIdByCode(siteCode);
        return id == null ? 0L : id;
    }

    @Override
    public Long findAnimalIdByCode(String animalCode) {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM animal WHERE animal_code = ?", Long.class, animalCode);
        return ids.stream().findFirst().orElse(null);
    }

    @Override
    public long requireAnimalId(String animalCode) {
        Long id = findAnimalIdByCode(animalCode);
        return id == null ? 0L : id;
    }

    @Override
    public String findSiteCodeById(long siteId) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT site_code FROM site WHERE id = ?", String.class, siteId);
        return codes.stream().findFirst().orElse(null);
    }

    @Override
    public String findAnimalCodeById(long animalId) {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT animal_code FROM animal WHERE id = ?", String.class, animalId);
        return codes.stream().findFirst().orElse(null);
    }

    @Override
    public void insertTag(String tagCode, long holderSiteId, Instant registeredAt) {
        jdbcTemplate.update(
                "INSERT INTO tag (tag_code, holder_site_id, registered_at) VALUES (?, ?, ?)",
                tagCode, holderSiteId, Timestamp.from(registeredAt));
    }

    @Override
    public void lockTag(long tagId) {
        jdbcTemplate.queryForObject("SELECT id FROM tag WHERE id = ? FOR UPDATE", Long.class, tagId);
    }

    @Override
    public void lockAnimal(long animalId) {
        jdbcTemplate.queryForObject("SELECT id FROM animal WHERE id = ? FOR UPDATE", Long.class,
                animalId);
    }

    @Override
    public int updateTagHolder(long tagId, long expectedHolderSiteId, long newHolderSiteId) {
        return jdbcTemplate.update(
                "UPDATE tag SET holder_site_id = ? WHERE id = ? AND holder_site_id = ?",
                newHolderSiteId, tagId, expectedHolderSiteId);
    }

    @Override
    public long insertAttachment(long animalId, long tagId, long siteId, Instant attachAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO attachment (animal_id, tag_id, site_id, attach_at) "
                            + "VALUES (?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, animalId);
            ps.setLong(2, tagId);
            ps.setLong(3, siteId);
            ps.setTimestamp(4, Timestamp.from(attachAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    public Attachment getAttachmentById(long attachmentId) {
        List<Attachment> attachments = jdbcTemplate.query(
                "SELECT id, animal_id, tag_id, site_id, attach_at, detach_at, detach_site_id "
                        + "FROM attachment WHERE id = ?",
                ATTACHMENT_MAPPER, attachmentId);
        return attachments.stream().findFirst().orElse(null);
    }

    @Override
    public Optional<Attachment> findOpenAttachmentByTag(long tagId) {
        List<Attachment> attachments = jdbcTemplate.query(
                "SELECT id, animal_id, tag_id, site_id, attach_at, detach_at, detach_site_id "
                        + "FROM attachment WHERE tag_id = ? AND detach_at IS NULL",
                ATTACHMENT_MAPPER, tagId);
        return attachments.stream().findFirst();
    }

    @Override
    public Instant findLatestAttachAtByTag(long tagId) {
        Timestamp value = jdbcTemplate.queryForObject(
                "SELECT MAX(attach_at) FROM attachment WHERE tag_id = ?",
                Timestamp.class, tagId);
        return value == null ? null : value.toInstant();
    }

    @Override
    public int closeAttachment(long attachmentId, long detachSiteId, Instant detachAt) {
        return jdbcTemplate.update(
                "UPDATE attachment SET detach_at = ?, detach_site_id = ? "
                        + "WHERE id = ? AND detach_at IS NULL AND attach_at < ?",
                Timestamp.from(detachAt), detachSiteId, attachmentId, Timestamp.from(detachAt));
    }

    @Override
    public List<Attachment> findAttachmentsByTag(long tagId) {
        return jdbcTemplate.query(
                "SELECT id, animal_id, tag_id, site_id, attach_at, detach_at, detach_site_id "
                        + "FROM attachment WHERE tag_id = ? ORDER BY attach_at, id",
                ATTACHMENT_MAPPER, tagId);
    }

    @Override
    public List<Attachment> findAttachmentsByAnimal(long animalId) {
        return jdbcTemplate.query(
                "SELECT id, animal_id, tag_id, site_id, attach_at, detach_at, detach_site_id "
                        + "FROM attachment WHERE animal_id = ? ORDER BY attach_at, id",
                ATTACHMENT_MAPPER, animalId);
    }

    @Override
    public boolean tagOccupiedAt(long tagId, Instant at) {
        // 左闭右开：attach_at <= at < detach_at；detach_at 为 NULL 表示持续有效。
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE tag_id = ? AND attach_at <= ? "
                        + "AND (detach_at IS NULL OR ? < detach_at)",
                Integer.class, tagId, Timestamp.from(at), Timestamp.from(at));
        return count != null && count > 0;
    }

    @Override
    public boolean animalOccupiedAt(long animalId, Instant at) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attachment WHERE animal_id = ? AND attach_at <= ? "
                        + "AND (detach_at IS NULL OR ? < detach_at)",
                Integer.class, animalId, Timestamp.from(at), Timestamp.from(at));
        return count != null && count > 0;
    }

    @Override
    public Optional<Handoff> findHandoffByCode(String handoffCode) {
        List<Handoff> handoffs = jdbcTemplate.query(
                "SELECT id, handoff_code, tag_id, from_site_id, to_site_id, status, "
                        + "initiated_at, confirmed_at, closed_at, reject_reason "
                        + "FROM handoff WHERE handoff_code = ?",
                HANDOFF_MAPPER, handoffCode);
        return handoffs.stream().findFirst();
    }

    @Override
    public Optional<Handoff> findPendingHandoffByTag(long tagId) {
        List<Handoff> handoffs = jdbcTemplate.query(
                "SELECT id, handoff_code, tag_id, from_site_id, to_site_id, status, "
                        + "initiated_at, confirmed_at, closed_at, reject_reason "
                        + "FROM handoff WHERE tag_id = ? AND status = 'PENDING' "
                        + "ORDER BY id DESC",
                HANDOFF_MAPPER, tagId);
        return handoffs.stream().findFirst();
    }

    @Override
    public Handoff getHandoffByCode(String handoffCode) {
        return findHandoffByCode(handoffCode).orElse(null);
    }

    @Override
    public long insertHandoff(String handoffCode, long tagId, long fromSiteId, long toSiteId,
                              Instant initiatedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO handoff (handoff_code, tag_id, from_site_id, to_site_id, "
                            + "status, initiated_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                    new String[] {"id"});
            ps.setString(1, handoffCode);
            ps.setLong(2, tagId);
            ps.setLong(3, fromSiteId);
            ps.setLong(4, toSiteId);
            ps.setTimestamp(5, Timestamp.from(initiatedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    @Override
    public Handoff getHandoffById(long handoffId) {
        List<Handoff> handoffs = jdbcTemplate.query(
                "SELECT id, handoff_code, tag_id, from_site_id, to_site_id, status, "
                        + "initiated_at, confirmed_at, closed_at, reject_reason "
                        + "FROM handoff WHERE id = ?",
                HANDOFF_MAPPER, handoffId);
        return handoffs.stream().findFirst().orElse(null);
    }

    @Override
    public int updateHandoffStatus(long handoffId, HandoffStatus expected, HandoffStatus next,
                                   Instant confirmedAt, Instant closedAt, String rejectReason) {
        StringBuilder sql = new StringBuilder("UPDATE handoff SET status = ?");
        if (confirmedAt != null) {
            sql.append(", confirmed_at = ?");
        }
        if (closedAt != null) {
            sql.append(", closed_at = ?");
        }
        if (rejectReason != null) {
            sql.append(", reject_reason = ?");
        }
        sql.append(" WHERE id = ? AND status = ?");

        List<Object> params = new java.util.ArrayList<>();
        params.add(next.name());
        if (confirmedAt != null) {
            params.add(Timestamp.from(confirmedAt));
        }
        if (closedAt != null) {
            params.add(Timestamp.from(closedAt));
        }
        if (rejectReason != null) {
            params.add(rejectReason);
        }
        params.add(handoffId);
        params.add(expected.name());
        return jdbcTemplate.update(sql.toString(), params.toArray());
    }

    @Override
    public List<Handoff> findHandoffsByTag(long tagId) {
        return jdbcTemplate.query(
                "SELECT id, handoff_code, tag_id, from_site_id, to_site_id, status, "
                        + "initiated_at, confirmed_at, closed_at, reject_reason "
                        + "FROM handoff WHERE tag_id = ? ORDER BY initiated_at, id",
                HANDOFF_MAPPER, tagId);
    }

    @Override
    public void insertIdempotentRequest(String requestId, String requestType, String requestHash) {
        jdbcTemplate.update(
                "INSERT INTO idempotent_request (request_id, request_type, request_hash, "
                        + "result_id, created_at) VALUES (?, ?, ?, 0, ?)",
                requestId, requestType, requestHash, Timestamp.from(Instant.now()));
    }

    @Override
    public void updateIdempotentResult(String requestId, String requestType, long resultId) {
        jdbcTemplate.update(
                "UPDATE idempotent_request SET result_id = ? "
                        + "WHERE request_id = ? AND request_type = ?",
                resultId, requestId, requestType);
    }

    @Override
    public Optional<IdempotentRequest> findIdempotentRequest(String requestId, String requestType) {
        List<IdempotentRequest> requests = jdbcTemplate.query(
                "SELECT id, request_id, request_type, request_hash, result_id, created_at "
                        + "FROM idempotent_request WHERE request_id = ? AND request_type = ?",
                IDEMPOTENT_MAPPER, requestId, requestType);
        return requests.stream().findFirst();
    }
}
