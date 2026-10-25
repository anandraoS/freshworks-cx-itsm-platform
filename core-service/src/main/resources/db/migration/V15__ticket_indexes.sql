-- Composite indexes matching the ticket list/filter/sort/analytics access paths (tenant_id always leads).
-- V16-V19 are intentionally unused; V20+ is reserved for the KB / AI modules.
create index ix_ticket_tenant_status_prio_created on ticket (tenant_id, status, priority, created_at);
create index ix_ticket_tenant_created on ticket (tenant_id, created_at);
create index ix_ticket_tenant_assignee_status on ticket (tenant_id, assignee_id, status);
create index ix_ticket_tenant_requester_created on ticket (tenant_id, requester_id, created_at);
create index ix_ticket_tenant_sla on ticket (tenant_id, sla_status);
create index ix_ticket_tenant_category on ticket (tenant_id, category_id);
create index ix_ticket_tenant_resolved on ticket (tenant_id, resolved_at);
create index ix_ticket_sla_scan on ticket (tenant_id, status, sla_checked_at);
