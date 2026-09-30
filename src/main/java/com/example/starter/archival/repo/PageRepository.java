package com.example.starter.archival.repo;

import com.example.starter.archival.domain.ArchivePage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 档案页登记仓储。
 */
@Repository
public class PageRepository {

    private static final RowMapper<ArchivePage> MAPPER = (rs, rowNum) -> new ArchivePage(
            rs.getLong("id"),
            rs.getString("accession_no"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public PageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 登记新档案页，返回主键。 */
    public long insert(String accessionNo, LocalDateTime createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO archive_page (accession_no, created_at) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, accessionNo);
            ps.setTimestamp(2, Timestamp.valueOf(createdAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询。 */
    public Optional<ArchivePage> findById(long id) {
        List<ArchivePage> list = jdbc.query(
                "SELECT id, accession_no, created_at FROM archive_page WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /** 按馆藏号查询。 */
    public Optional<ArchivePage> findByAccessionNo(String accessionNo) {
        List<ArchivePage> list = jdbc.query(
                "SELECT id, accession_no, created_at FROM archive_page WHERE accession_no = ?",
                MAPPER, accessionNo);
        return list.stream().findFirst();
    }
}
