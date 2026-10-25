import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settingsApi } from '../../api/endpoints';
import { describeError } from '../../api/client';
import { useTenantId } from '../../auth/AuthContext';
import { Button, Card, ErrorNotice, Input, Skeleton, useToast } from '../../components/ui';
import { applyFieldErrors } from '../../lib/formErrors';
import { qk } from '../../lib/queryKeys';
import { settingsSchema, type SettingsForm } from '../../lib/schemas';

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export default function SettingsPage() {
  const t = useTenantId();
  const qc = useQueryClient();
  const toast = useToast();
  const [formError, setFormError] = useState<string | null>(null);
  const q = useQuery({ queryKey: qk.settings(t), queryFn: settingsApi.get });
  const { register, handleSubmit, reset, setError, formState: { errors, isDirty } } = useForm<SettingsForm>({ resolver: zodResolver(settingsSchema) });

  useEffect(() => {
    const s = q.data;
    if (s) reset({
      name: s.name, timezone: s.timezone, businessHoursStart: s.businessHoursStart, businessHoursEnd: s.businessHoursEnd,
      businessDays: s.businessDays, defaultLanguage: s.defaultLanguage,
      emailProvider: s.integrations?.emailProvider ?? '', slackWebhookUrl: s.integrations?.slackWebhookUrl ?? '', aiProvider: s.integrations?.aiProvider ?? '',
    });
  }, [q.data, reset]);

  const m = useMutation({
    mutationFn: (v: SettingsForm) => settingsApi.update({
      name: v.name.trim(), timezone: v.timezone.trim(), businessHoursStart: v.businessHoursStart, businessHoursEnd: v.businessHoursEnd,
      businessDays: v.businessDays, defaultLanguage: v.defaultLanguage.trim(),
      integrations: { emailProvider: v.emailProvider.trim(), slackWebhookUrl: v.slackWebhookUrl.trim(), aiProvider: v.aiProvider.trim() },
    }),
    onSuccess: () => { void qc.invalidateQueries({ queryKey: qk.settings(t) }); toast.push('success', 'Settings saved'); },
    onError: (e) => { if (!applyFieldErrors(e, setError, ['name', 'timezone', 'businessHoursStart', 'businessHoursEnd', 'defaultLanguage'])) setFormError(describeError(e)); },
  });

  if (q.isLoading) return <Skeleton lines={8} label="Loading settings" />;
  if (q.isError) return <ErrorNotice message={describeError(q.error)} onRetry={() => void q.refetch()} />;

  return (
    <Card title="Organization settings">
      <form onSubmit={handleSubmit((v) => { setFormError(null); m.mutate(v); })} noValidate aria-label="Organization settings" className="form-grid">
        <Input label="Organization name" required {...register('name')} error={errors.name?.message} />
        <Input label="Timezone" required hint="IANA name, e.g. UTC or America/New_York" {...register('timezone')} error={errors.timezone?.message} />
        <Input label="Business hours start" type="time" required {...register('businessHoursStart')} error={errors.businessHoursStart?.message} />
        <Input label="Business hours end" type="time" required {...register('businessHoursEnd')} error={errors.businessHoursEnd?.message} />
        <Input label="Default language" required {...register('defaultLanguage')} error={errors.defaultLanguage?.message} />
        <fieldset className="span-2 fieldset">
          <legend>Business days</legend>
          <div className="check-row">
            {DAYS.map((d) => <label key={d}><input type="checkbox" value={d} {...register('businessDays')} /> {d.charAt(0) + d.slice(1).toLowerCase()}</label>)}
          </div>
          {errors.businessDays && <p className="field-msg" role="alert">{errors.businessDays.message}</p>}
        </fieldset>
        <fieldset className="span-2 fieldset">
          <legend>Integrations (dummy values only)</legend>
          <p className="muted small">Use placeholder values for demos. Never enter real credentials or production webhook URLs here.</p>
          <div className="form-grid">
            <Input label="Email provider" placeholder="LOG_ONLY" {...register('emailProvider')} error={errors.emailProvider?.message} />
            <Input label="AI provider" placeholder="MOCK_RULE_BASED" {...register('aiProvider')} error={errors.aiProvider?.message} />
            <div className="span-2"><Input label="Slack webhook URL" type="url" placeholder="https://example.invalid/webhook" {...register('slackWebhookUrl')} error={errors.slackWebhookUrl?.message} /></div>
          </div>
        </fieldset>
        {formError && <p className="notice notice-error span-2" role="alert">{formError}</p>}
        <div className="span-2 form-actions"><Button type="submit" loading={m.isPending} disabled={!isDirty || m.isPending}>Save settings</Button></div>
      </form>
    </Card>
  );
}
