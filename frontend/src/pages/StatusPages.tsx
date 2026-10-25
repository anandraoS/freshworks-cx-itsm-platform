import { Link } from 'react-router-dom';

function StatusPage({ code, title, text }: { code: string; title: string; text: string }) {
  return (
    <div className="page-center">
      <p className="status-code" aria-hidden="true">{code}</p>
      <h1>{title}</h1>
      <p>{text}</p>
      <Link className="btn btn-primary btn-md" to="/">Go to home</Link>
    </div>
  );
}

export function UnauthorizedPage() {
  return <StatusPage code="401" title="Unauthorized" text="You need to sign in to view this page." />;
}
export function ForbiddenPage() {
  return <StatusPage code="403" title="Forbidden" text="Your role does not have access to this page." />;
}
export function NotFoundPage() {
  return <StatusPage code="404" title="Page not found" text="The page you are looking for does not exist." />;
}
