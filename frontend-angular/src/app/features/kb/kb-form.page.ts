import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { concatMap, Observable, of } from 'rxjs';
import { KbApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { buildArticleForm } from '../../core/form-schemas';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import type { Article } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { applyFieldErrors, validateAll } from '../../core/validators';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';

@Component({
  selector: 'app-kb-form-page',
  imports: [ReactiveFormsModule, RouterLink, ButtonComponent, CardComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent],
  template: `
    @if (editing() && existing.loading() && !existing.data()) { <app-skeleton [lines]="8" label="Loading article" /> }
    @if (editing() && existing.error(); as e) { <app-error-notice [message]="describe(e)" /> }
    @if (!editing() || existing.data()) {
      <div class="page-head"><h1>{{ editing() ? 'Edit article' : 'New article' }}</h1></div>
      <app-card>
        <form [formGroup]="form" (ngSubmit)="save(false)" novalidate aria-label="Article form" class="form-grid">
          <div class="span-2"><app-field label="Title" [control]="form.controls.title" required><input appFieldControl formControlName="title" /></app-field></div>
          <div class="span-2"><app-field label="Summary" [control]="form.controls.summary"><input appFieldControl formControlName="summary" /></app-field></div>
          <div class="span-2"><app-field label="Body" [control]="form.controls.body" required><textarea appFieldControl formControlName="body" rows="12"></textarea></app-field></div>
          <app-field label="Category" [control]="form.controls.categoryId">
            <select appFieldControl formControlName="categoryId">
              <option value="">Uncategorized</option>
              @for (c of lookups.articleCategories(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
            </select>
          </app-field>
          <app-field label="Visibility" hint="Internal articles are never shown to customers" [control]="form.controls.visibility">
            <select appFieldControl formControlName="visibility"><option value="PUBLIC">Public</option><option value="INTERNAL">Internal</option></select>
          </app-field>
          @if (formError(); as m) { <p class="notice notice-error span-2" role="alert">{{ m }}</p> }
          <div class="span-2 form-actions">
            <button appButton type="submit" variant="secondary" [loading]="busy() && !publishing()" [disabled]="busy()">Save as draft</button>
            <button appButton [loading]="publishing()" [disabled]="busy()" (click)="save(true)">Save &amp; publish</button>
            <a class="btn btn-ghost btn-md" [routerLink]="editing() ? ['/kb', id()] : ['/kb']">Cancel</a>
          </div>
        </form>
      </app-card>
    }
  `,
})
export class KbFormPage {
  private readonly api = inject(KbApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly lookups = inject(LookupsService);
  readonly id = input<string | undefined>(undefined);
  protected readonly editing = () => !!this.id();
  protected readonly form = buildArticleForm();
  protected readonly busy = signal(false);
  protected readonly publishing = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly describe = describeError;
  protected readonly existing = loadable(
    () => this.api.get(this.id() as string),
    (a) => this.form.reset({ title: a.title, summary: a.summary ?? '', body: a.body, categoryId: a.categoryId ?? '', visibility: a.visibility }),
  );

  constructor() {
    this.lookups.ensureArticleCategories();
    effect(() => { if (this.id()) untracked(() => this.existing.load()); });
  }

  protected save(publish: boolean): void {
    if (this.busy() || !validateAll(this.form)) return;
    this.formError.set(null);
    this.busy.set(true);
    this.publishing.set(publish);
    const v = this.form.getRawValue();
    const body = { title: v.title.trim(), summary: v.summary.trim(), body: v.body.trim(), categoryId: v.categoryId || null, visibility: v.visibility };
    const saved$: Observable<Article> = this.editing() ? this.api.update(this.id() as string, body) : this.api.create(body);
    saved$.pipe(concatMap((a) => (publish ? this.api.publish(a.id) : of(a)))).subscribe({
      next: (a) => {
        this.busy.set(false); this.publishing.set(false);
        this.toast.success(publish ? 'Article published' : 'Draft saved');
        void this.router.navigate(['/kb', a.id]);
      },
      error: (e: unknown) => {
        this.busy.set(false); this.publishing.set(false);
        if (!applyFieldErrors(e, this.form, ['title', 'summary', 'body', 'categoryId', 'visibility'])) this.formError.set(describeError(e));
      },
    });
  }
}
