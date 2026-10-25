import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { kbApi } from '../api/endpoints';
import { describeError } from '../api/client';
import { useTenantId } from '../auth/AuthContext';
import { Button, Card, ErrorNotice, Input, Select, Skeleton, Textarea, useToast } from '../components/ui';
import { useArticleCategories } from '../hooks/useLookups';
import { applyFieldErrors } from '../lib/formErrors';
import { qk } from '../lib/queryKeys';
import { articleSchema, type ArticleForm } from '../lib/schemas';

export default function KbFormPage() {
  const { id } = useParams();
  const editing = !!id;
  const t = useTenantId();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const toast = useToast();
  const cats = useArticleCategories();
  const [formError, setFormError] = useState<string | null>(null);
  const existing = useQuery({ queryKey: qk.article(t, id ?? 'new'), queryFn: () => kbApi.get(id as string), enabled: editing });
  const { register, handleSubmit, reset, setError, formState: { errors } } = useForm<ArticleForm>({
    resolver: zodResolver(articleSchema),
    defaultValues: { title: '', summary: '', body: '', categoryId: '', visibility: 'PUBLIC' },
  });
  useEffect(() => {
    const a = existing.data;
    if (a) reset({ title: a.title, summary: a.summary, body: a.body, categoryId: a.categoryId ?? '', visibility: a.visibility });
  }, [existing.data, reset]);

  const save = useMutation({
    mutationFn: async ({ v, publish }: { v: ArticleForm; publish: boolean }) => {
      const body = { title: v.title.trim(), summary: v.summary.trim(), body: v.body.trim(), categoryId: v.categoryId || null, visibility: v.visibility as 'PUBLIC' | 'INTERNAL' };
      const saved = editing ? await kbApi.update(id as string, body) : await kbApi.create(body);
      return publish ? kbApi.publish(saved.id) : saved;
    },
    onSuccess: (a, { publish }) => {
      void qc.invalidateQueries({ queryKey: qk.articles(t) });
      toast.push('success', publish ? 'Article published' : 'Draft saved');
      navigate(`/kb/${a.id}`);
    },
    onError: (e) => { if (!applyFieldErrors(e, setError, ['title', 'summary', 'body', 'categoryId', 'visibility'])) setFormError(describeError(e)); },
  });

  if (editing && existing.isLoading) return <Skeleton lines={8} label="Loading article" />;
  if (editing && existing.isError) return <ErrorNotice message={describeError(existing.error)} />;

  const submit = (publish: boolean) => handleSubmit((v) => { setFormError(null); save.mutate({ v, publish }); });
  return (
    <div>
      <div className="page-head"><h1>{editing ? 'Edit article' : 'New article'}</h1></div>
      <Card>
        <form onSubmit={submit(false)} noValidate aria-label="Article form" className="form-grid">
          <div className="span-2"><Input label="Title" required {...register('title')} error={errors.title?.message} /></div>
          <div className="span-2"><Input label="Summary" {...register('summary')} error={errors.summary?.message} /></div>
          <div className="span-2"><Textarea label="Body" required rows={12} {...register('body')} error={errors.body?.message} /></div>
          <Select label="Category" {...register('categoryId')}>
            <option value="">Uncategorized</option>
            {cats.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </Select>
          <Select label="Visibility" {...register('visibility')} hint="Internal articles are never shown to customers">
            <option value="PUBLIC">Public</option><option value="INTERNAL">Internal</option>
          </Select>
          {formError && <p className="notice notice-error span-2" role="alert">{formError}</p>}
          <div className="span-2 form-actions">
            <Button type="submit" variant="secondary" loading={save.isPending} disabled={save.isPending}>Save as draft</Button>
            <Button onClick={() => void submit(true)()} disabled={save.isPending}>Save &amp; publish</Button>
            <Link className="btn btn-ghost btn-md" to={editing ? `/kb/${id}` : '/kb'}>Cancel</Link>
          </div>
        </form>
      </Card>
    </div>
  );
}
