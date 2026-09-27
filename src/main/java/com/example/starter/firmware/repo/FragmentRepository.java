package com.example.starter.firmware.repo;

import com.example.starter.firmware.domain.ReleaseFragment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 发布分片登记数据访问。仅提供批量插入与只读查询，登记后不可修改、不可删除。
 */
@Repository
public class FragmentRepository {

    private static final RowMapper<ReleaseFragment> MAPPER = (rs, rowNum) ->
            new ReleaseFragment(rs.getLong("release_id"), rs.getInt("seq_no"), rs.getString("digest"));

    private final JdbcTemplate jdbc;

    public FragmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 批量登记分片，须与发布单插入处于同一事务。
     */
    public void insertBatch(long releaseId, List<ReleaseFragment> fragments) {
        jdbc.batchUpdate("INSERT INTO release_fragment (release_id, seq_no, digest) VALUES (?, ?, ?)",
                fragments, fragments.size(), (ps, fragment) -> {
                    ps.setLong(1, releaseId);
                    ps.setInt(2, fragment.seqNo());
                    ps.setString(3, fragment.digest());
                });
    }

    /**
     * 按规范顺序（序号升序）返回登记分片。
     */
    public List<ReleaseFragment> findByRelease(long releaseId) {
        return jdbc.query("SELECT release_id, seq_no, digest FROM release_fragment"
                + " WHERE release_id = ? ORDER BY seq_no", MAPPER, releaseId);
    }

    public int countByRelease(long releaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_fragment WHERE release_id = ?", Integer.class, releaseId);
        return count == null ? 0 : count;
    }
}
