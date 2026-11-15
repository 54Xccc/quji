ALTER TABLE tb_shop
  ADD COLUMN event_category VARCHAR(32) NULL,
  ADD COLUMN venue_name VARCHAR(128) NULL,
  ADD COLUMN event_description VARCHAR(2000) NULL,
  ADD INDEX idx_event_category (event_category, id);

ALTER TABLE tb_voucher
  ADD COLUMN session_starts_at DATETIME NULL,
  ADD COLUMN session_ends_at DATETIME NULL;

ALTER TABLE tb_voucher_order
  ADD COLUMN admission_code CHAR(36) NULL,
  ADD COLUMN checked_in_by BIGINT NULL,
  ADD UNIQUE INDEX uq_order_admission_code (admission_code);
