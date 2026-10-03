-- 每个用例前清理业务数据、重置自增序列并重建合成基础数据
DELETE FROM takeoff_clearance;
DELETE FROM deicing_completion;
DELETE FROM deicing_slot;
DELETE FROM flight;
DELETE FROM deicing_vehicle;
DELETE FROM airport;

ALTER TABLE takeoff_clearance ALTER COLUMN id RESTART WITH 1;
ALTER TABLE deicing_completion ALTER COLUMN id RESTART WITH 1;
ALTER TABLE deicing_slot ALTER COLUMN id RESTART WITH 1;
ALTER TABLE flight ALTER COLUMN id RESTART WITH 1;
ALTER TABLE deicing_vehicle ALTER COLUMN id RESTART WITH 1;
ALTER TABLE airport ALTER COLUMN id RESTART WITH 1;

INSERT INTO airport (id, code, name) VALUES
    (1, 'ZBAA', '合成首都机场'),
    (2, 'ZSPD', '合成浦东机场');

INSERT INTO flight (id, flight_no, airport_id) VALUES
    (1, 'CA1001', 1),
    (2, 'CA1002', 1),
    (3, 'MU2001', 2);

INSERT INTO deicing_vehicle (id, code, name) VALUES
    (1, 'DEICE-01', '一号除冰车'),
    (2, 'DEICE-02', '二号除冰车');