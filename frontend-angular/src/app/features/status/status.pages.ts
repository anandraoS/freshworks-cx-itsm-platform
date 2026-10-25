import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-status-page',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page-center">
      <p class="status-code" aria-hidden="true">{{ code() }}</p>
      <h1>{{ heading() }}</h1>
      <p>{{ text() }}</p>
      <a class="btn btn-primary btn-md" routerLink="/">Go to home</a>
    </div>
  `,
})
export class StatusPage {
  readonly code = input.required<string>();
  readonly heading = input.required<string>();
  readonly text = input.required<string>();
}

@Component({
  selector: 'app-unauthorized-page', imports: [StatusPage], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-status-page code="401" heading="Unauthorized" text="You need to sign in to view this page." />`,
})
export class UnauthorizedPage {}

@Component({
  selector: 'app-forbidden-page', imports: [StatusPage], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-status-page code="403" heading="Forbidden" text="Your role does not have access to this page." />`,
})
export class ForbiddenPage {}

@Component({
  selector: 'app-not-found-page', imports: [StatusPage], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<app-status-page code="404" heading="Page not found" text="The page you are looking for does not exist." />`,
})
export class NotFoundPage {}
