import { Component, inject, OnInit, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { CategoryApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { buildCategoryForm } from '../../core/form-schemas';
import { humanize } from '../../core/format';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { Category, PRIORITIES, TICKET_TYPES } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { validateAll } from '../../core/validators';
import { PriorityBadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { ConfirmDialogComponent } from '../../shared/ui/confirm-dialog';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';
import { ModalComponent } from '../../shared/ui/modal';
import { Column, TableComponent } from '../../shared/ui/table';

@Component({
  selector: 'app-categories-page',
  imports: [ReactiveFormsModule, ButtonComponent, CardComponent, ModalComponent, ConfirmDialogComponent, TableComponent, PriorityBadgeComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  template: `
    <app-card title="Ticket categories">
      <button cardActions appButton (click)="openForm('new')">Add category</button>
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="4" label="Loading categories" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
      @if (q.data(); as cats) {
        @if (cats.length === 0) { <app-empty-state title="No categories yet" /> }
        @else {
          <app-table caption="Ticket categories" [columns]="columns" [rows]="cats">
            <ng-template #cell let-c let-col="col">
              @switch (col) {
                @case ('name') { {{ c.name }} }
                @case ('type') { {{ human(c.type) }} }
                @case ('prio') { <app-priority-badge [priority]="c.defaultPriority" /> }
                @case ('actions') {
                  <span class="btn-row">
                    <button appButton size="sm" variant="secondary" [attr.aria-label]="'Edit ' + c.name" (click)="openForm(c)">Edit</button>
                    <button appButton size="sm" variant="danger" [attr.aria-label]="'Delete ' + c.name" (click)="deleting.set(c)">Delete</button>
                  </span>
                }
              }
            </ng-template>
          </app-table>
        }
      }
      @if (editing(); as ed) {
        <app-modal [title]="ed === 'new' ? 'Add category' : 'Edit category'" (closed)="closeForm()">
          <form [formGroup]="form" (ngSubmit)="save()" novalidate aria-label="Category form">
            <app-field label="Name" [control]="form.controls.name" required><input appFieldControl formControlName="name" data-autofocus /></app-field>
            <app-field label="Type" [control]="form.controls.type"><select appFieldControl formControlName="type">@for (x of types; track x) { <option [value]="x">{{ human(x) }}</option> }</select></app-field>
            <app-field label="Default priority" [control]="form.controls.defaultPriority"><select appFieldControl formControlName="defaultPriority">@for (x of priorities; track x) { <option [value]="x">{{ human(x) }}</option> }</select></app-field>
            @if (formError(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
            <div class="modal-footer">
              <button appButton variant="secondary" (click)="closeForm()">Cancel</button>
              <button appButton type="submit" [loading]="busy()">Save</button>
            </div>
          </form>
        </app-modal>
      }
      @if (deleting(); as d) {
        <app-confirm-dialog title="Delete category?" confirmLabel="Delete" danger [busy]="busy()" (confirmed)="remove(d)" (cancelled)="deleting.set(null)">
          <p>Delete <strong>{{ d.name }}</strong>? Existing tickets keep their history.</p>
        </app-confirm-dialog>
      }
    </app-card>
  `,
})
export class CategoriesPage implements OnInit {
  private readonly api = inject(CategoryApi);
  private readonly toast = inject(ToastService);
  private readonly lookups = inject(LookupsService);
  protected readonly types = TICKET_TYPES;
  protected readonly priorities = PRIORITIES;
  protected readonly human = humanize;
  protected readonly describe = describeError;
  protected readonly columns: Column[] = [{ key: 'name', header: 'Name' }, { key: 'type', header: 'Type' }, { key: 'prio', header: 'Default priority' }, { key: 'actions', header: 'Actions' }];
  protected readonly q = loadable(() => this.api.list());
  protected readonly editing = signal<Category | 'new' | null>(null);
  protected readonly deleting = signal<Category | null>(null);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected form = buildCategoryForm();

  ngOnInit(): void { this.q.load(); }

  protected openForm(c: Category | 'new'): void {
    this.formError.set(null);
    this.form = c === 'new' ? buildCategoryForm() : buildCategoryForm(c);
    this.editing.set(c);
  }
  protected closeForm(): void { this.editing.set(null); }

  protected save(): void {
    const ed = this.editing();
    if (!ed || this.busy() || !validateAll(this.form)) return;
    const v = this.form.getRawValue();
    const body = { name: v.name.trim(), type: v.type, defaultPriority: v.defaultPriority };
    this.busy.set(true);
    (ed === 'new' ? this.api.create(body) : this.api.update(ed.id, body)).subscribe({
      next: () => { this.busy.set(false); this.editing.set(null); this.refresh(ed === 'new' ? 'Category created' : 'Category updated'); },
      error: (e: unknown) => { this.busy.set(false); this.formError.set(describeError(e)); },
    });
  }

  protected remove(c: Category): void {
    this.busy.set(true);
    this.api.remove(c.id).subscribe({
      next: () => { this.busy.set(false); this.deleting.set(null); this.refresh('Category deleted'); },
      error: (e: unknown) => { this.busy.set(false); this.deleting.set(null); this.toast.error(describeError(e)); },
    });
  }

  private refresh(msg: string): void { this.q.load(); this.lookups.refreshCategories(); this.toast.success(msg); }
}
