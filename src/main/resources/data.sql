-- 合成基础数据：机场、航班、除冰车（幂等，重复初始化不报错）
INSERT INTO airport (code, name)
    SELECT 'ZBAA', '合成首都机场' WHERE NOT EXISTS (SELECT 1 FROM airport WHERE code = 'ZBAA');
INSERT INTO airport (code, name)
    SELECT 'ZSPD', '合成浦东机场' WHERE NOT EXISTS (SELECT 1 FROM airport WHERE code = 'ZSPD');

INSERT INTO flight (flight_no, airport_id)
    SELECT 'CA1001', id FROM airport WHERE code = 'ZBAA'
        AND NOT EXISTS (SELECT 1 FROM flight WHERE flight_no = 'CA1001');
INSERT INTO flight (flight_no, airport_id)
    SELECT 'CA1002', id FROM airport WHERE code = 'ZBAA'
        AND NOT EXISTS (SELECT 1 FROM flight WHERE flight_no = 'CA1002');
INSERT INTO flight (flight_no, airport_id)
    SELECT 'MU2001', id FROM airport WHERE code = 'ZSPD'
        AND NOT EXISTS (SELECT 1 FROM flight WHERE flight_no = 'MU2001');

INSERT INTO deicing_vehicle (code, name)
    SELECT 'DEICE-01', '一号除冰车' WHERE NOT EXISTS (SELECT 1 FROM deicing_vehicle WHERE code = 'DEICE-01');
INSERT INTO deicing_vehicle (code, name)
    SELECT 'DEICE-02', '二号除冰车' WHERE NOT EXISTS (SELECT 1 FROM deicing_vehicle WHERE code = 'DEICE-02');