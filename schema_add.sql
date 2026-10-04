
-- 制品版本声明的锁外能力（0~3 个，登记后不可改）。
CREATE TABLE IF NOT EXISTS artifact_capability (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    artifact_id BIGINT      NOT NULL,
    capability  VARCHAR(64) NOT NULL,
    CONSTRAINT fk_cap_artifact FOREIGN `artifact_id` (id)
);
COMMENT ON TABLE artifact_capability IS 'rzhh'