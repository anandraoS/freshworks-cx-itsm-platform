import { Component, inject, OnInit, signal } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { UserApi } from '../../core/endpoints';
import { describeError } from '../../core/errors';
import { buildUserCreateForm } from '../../core/form-schemas';
import { loadable } from '../../core/loadable';
import { LookupsService } from '../../core/lookups.service';
import { ManagedUser, Role, ROLES } from '../../core/models';
import { ToastService } from '../../core/toast.service';
import { applyFieldErrors, validateAll } from '../../core/validators';
import { BadgeComponent } from '../../shared/ui/badges';
import { ButtonComponent } from '../../shared/ui/button';
import { CardComponent } from '../../shared/ui/card';
import { EmptyStateComponent, ErrorNoticeComponent, SkeletonComponent } from '../../shared/ui/feedback';
import { FieldComponent, FieldControlDirective } from '../../shared/ui/field';
import { ModalComponent } from '../../shared/ui/modal';
import { Column, TableComponent } from '../../shared/ui/table';

@Component({
  selector: 'app-users-page',
  imports: [ReactiveFormsModule, ButtonComponent, CardComponent, BadgeComponent, ModalComponent, TableComponent, FieldComponent, FieldControlDirective, SkeletonComponent, ErrorNoticeComponent, EmptyStateComponent],
  template: `
    <app-card title="Users & roles">
      <button cardActions appButton (click)="creating.set(true)">Add user</button>
      @if (q.loading() && !q.data()) { <app-skeleton [lines]="5" label="Loading users" /> }
      @if (q.error(); as e) { <app-error-notice [message]="describe(e)" retryable (retry)="q.load()" /> }
      @if (q.data(); as users) {
        @if (users.length === 0) { <app-empty-state title="No users" /> }
        @else {
          <app-table caption="Users" [columns]="columns" [rows]="users">
            <ng-template #cell let-u let-col="col">
              @switch (col) {
                @case ('name') { {{ u.displayName }} }
                @case ('email') { {{ u.email }} }
                @case ('roles') { @for (r of u.roles; track r) { <app-badge tone="info">{{ r }}</app-badge> } }
                @case ('active') { <app-badge [tone]="u.active ? 'success' : 'neutral'">{{ u.active ? 'Active' : 'Inactive' }}</app-badge> }
                @case ('actions') { <button appButton size="sm" variant="secondary" [attr.aria-label]="'Edit ' + u.displayName" (click)="startEdit(u)">Edit</button> }
              }
            </ng-template>
          </app-table>
        }
      }

      @if (creating()) {
        <app-modal title="Add user" (closed)="closeCreate()">
          <form [formGroup]="createForm" (ngSubmit)="create()" novalidate aria-label="Add user">
            <app-field label="Email" [control]="createForm.controls.email" required><input appFieldControl formControlName="email" type="email" data-autofocus /></app-field>
            <app-field label="Display name" [control]="createForm.controls.displayName" required><input appFieldControl formControlName="displayName" /></app-field>
            <app-field label="Initial password" [control]="createForm.controls.password" required><input appFieldControl formControlName="password" type="password" autocomplete="new-password" /></app-field>
            <app-field label="Role" [control]="createForm.controls.role" required>
              <select appFieldControl formControlName="role">@for (r of roles; track r) { <option [value]="r">{{ r }}</option> }</select>
            </app-field>
            @if (formError(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
            <div class="modal-footer">
              <button appButton variant="secondary" (click)="closeCreate()">Cancel</button>
              <button appButton type="submit" [loading]="busy()">Create user</button>
            </div>
          </form>
        </app-modal>
      }

      @if (editing(); as u) {
        <app-modal [title]="'Edit ' + u.email" (closed)="editing.set(null)">
          <div class="field">
            <label for="eu-name">Display name</label>
            <input id="eu-name" [value]="editName()" data-autofocus (input)="editName.set($any($event.target).value)" />
            @if (editName().trim().length < 2) { <p class="field-msg" role="alert">Name is required</p> }
          </div>
          <fieldset class="fieldset">
            <legend>Roles</legend>
            <div class="check-row">
              @for (r of roles; track r) {
                <label><input type="checkbox" [checked]="editRoles().includes(r)" (change)="toggleRole(r, $any($event.target).checked)" /> {{ r }}</label>
              }
            </div>
            @if (editRoles().length === 0) { <p class="field-msg" role="alert">Select at least one role</p> }
          </fieldset>
          <label class="inline-check"><input type="checkbox" [checked]="editActive()" (change)="editActive.set($any($event.target).checked)" /> Active</label>
          @if (formError(); as m) { <p class="notice notice-error" role="alert">{{ m }}</p> }
          <div class="modal-footer">
            <button appButton variant="secondary" (click)="editing.set(null)">Cancel</button>
            <button appButton [loading]="busy()" [disabled]="editName().trim().length < 2 || editRoles().length === 0" (click)="saveEdit(u)">Save</button>
          </div>
        </app-modal>
      }
    </app-card>
  `,
})
export class UsersPage implements OnInit {
  private readonly api = inject(UserApi);
  private readonly toast = inject(ToastService);
  private readonly lookups = inject(LookupsService);
  protected readonly roles = ROLES;
  protected readonly columns: Column[] = [
    { key: 'name', header: 'Name' }, { key: 'email', header: 'Email' }, { key: 'roles', header: 'Roles' },
    { key: 'active', header: 'Status' }, { key: 'actions', header: 'Actions' },
  ];
  protected readonly q = loadable(() => this.api.list());
  protected readonly describe = describeError;
  protected readonly creating = signal(false);
  protected readonly editing = signal<ManagedUser | null>(null);
  protected readonly busy = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected createForm = buildUserCreateForm();
  protected readonly editName = signal('');
  protected readonly editRoles = signal<Role[]>([]);
  protected readonly editActive = signal(true);

  ngOnInit(): void { this.q.load(); }

  protected closeCreate(): void { this.creating.set(false); this.formError.set(null); this.createForm = buildUserCreateForm(); }

  protected create(): void {
    if (this.busy() || !validateAll(this.createForm)) return;
    const v = this.createForm.getRawValue();
    this.busy.set(true);
    this.formError.set(null);
    this.api.create({ email: v.email.trim(), displayName: v.displayName.trim(), roles: [v.role], password: v.password }).subscribe({
      next: () => { this.busy.set(false); this.closeCreate(); this.done('User created'); },
      error: (e: unknown) => {
        this.busy.set(false);
        if (!applyFieldErrors(e, this.createForm, ['email', 'displayName', 'password'])) this.formError.set(describeError(e));
      },
    });
  }

  protected startEdit(u: ManagedUser): void {
    this.formError.set(null);
    this.editName.set(u.displayName);
    this.editRoles.set([...u.roles]);
    this.editActive.set(u.active);
    this.editing.set(u);
  }
  protected toggleRole(r: Role, on: boolean): void { this.editRoles.update((l) => (on ? [...l, r] : l.filter((x) => x !== r))); }

  protected saveEdit(u: ManagedUser): void {
    this.busy.set(true);
    this.api.update(u.id, { displayName: this.editName().trim(), roles: this.editRoles(), active: this.editActive() }).subscribe({
      next: () => { this.busy.set(false); this.editing.set(null); this.done('User updated'); },
      error: (e: unknown) => { this.busy.set(false); this.formError.set(describeError(e)); },
    });
  }

  private done(msg: string): void { this.q.load(); this.lookups.refreshUsers(); this.toast.success(msg); }
}
