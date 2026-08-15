ALTER TABLE public.whatsapp_inbound_messages
    ADD COLUMN failure_stage VARCHAR(48);

ALTER TABLE public.whatsapp_inbound_messages
    ADD COLUMN failure_type VARCHAR(160);
