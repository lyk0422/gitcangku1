package com.example.starter.baggage;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动自动初始化验证：使用独立命名的 H2 内存库（MODE=MySQL），
 * 上下文启动时自动建表并执行 data.sql，验证合成数据无需外部服务即可就绪。
 * 该库仅存在于同一 JVM 内，随上下文结束释放。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:startup_seed_layout;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.application.name=gitcangku1-seed-test"
})
class SyntheticDataStartupTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startup_seedsTenByTenFloorAndTwoFiveByTenBags() {
        Map<String, Object> floor = jdbcTemplate.queryForMap(
                "SELECT width_mm, height_mm FROM floor_layout WHERE leg_id = 'LAYOUT-10'");
        assertThat(floor.get("width_mm")).isEqualTo(10);
        assertThat(floor.get("height_mm")).isEqualTo(10);

        Map<String, Object> status = jdbcTemplate.queryForMap(
                "SELECT status, version FROM leg WHERE leg_id = 'LAYOUT-10'");
        assertThat(status.get("status")).isEqualTo("OPEN");
        assertThat(status.get("version")).isEqualTo(1);

        List<Integer> sizes = jdbcTemplate.query(
                "SELECT width_mm, height_mm FROM bag_size WHERE bag_tag IN ('BAG-LEFT', 'BAG-RIGHT')"
                        + " ORDER BY bag_tag",
                (rs, n) -> List.of(rs.getInt(1), rs.getInt(2))).stream().flatMap(List::stream).toList();
        assertThat(sizes).containsExactly(5, 10, 5, 10);

        Integer itineraries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_itinerary WHERE leg_id = 'LAYOUT-10'", Integer.class);
        assertThat(itineraries).isEqualTo(2);

        // 无二维配置的旧航段与无尺寸行李同时就绪，验证兼容路径
        Map<String, Object> legacy = jdbcTemplate.queryForMap(
                "SELECT origin, destination FROM leg WHERE leg_id = 'LEGACY-1'");
        assertThat(legacy.get("origin")).isEqualTo("CAN");
        assertThat(legacy.get("destination")).isEqualTo("XIY");
        Integer legacyFloor = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM floor_layout WHERE leg_id = 'LEGACY-1'", Integer.class);
        assertThat(legacyFloor).isZero();
        Integer legacySize = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bag_size WHERE bag_tag = 'BAG-LEGACY'", Integer.class);
        assertThat(legacySize).isZero();
    }
}
