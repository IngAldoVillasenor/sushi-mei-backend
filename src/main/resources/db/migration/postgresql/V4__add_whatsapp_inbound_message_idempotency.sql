CREATE TABLE public.whatsapp_inbound_messages (
    message_id VARCHAR(255) NOT NULL,
    phone_number VARCHAR(32) NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    processing_status VARCHAR(32) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT whatsapp_inbound_messages_pkey PRIMARY KEY (message_id),
    CONSTRAINT whatsapp_inbound_messages_processing_status_check
        CHECK (processing_status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);
