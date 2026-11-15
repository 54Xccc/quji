ALTER TABLE tb_business_outbox ADD COLUMN delivery_id VARCHAR(64) NULL;

-- After stopping the old application, hand unfinished broker deliveries to Kafka.
-- Keep DONE/REVIEW, Inbox and business IDs intact.
UPDATE tb_business_outbox
SET state='PENDING', lease_owner=NULL, lease_until=NULL, sent_at=NULL,
    next_attempt_at=GREATEST(next_attempt_at,COALESCE(deliver_at,NOW(3)),NOW(3))
WHERE state IN ('SENT','SENDING');
