import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props { children: ReactNode; fallback?: ReactNode; resetKey?: string }
interface State { error: Error | null }

/** Catches render errors in the subtree; resets automatically when `resetKey` (e.g. the route) changes. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Only the message + component stack are logged; never request bodies or tokens.
    console.error('UI error boundary caught:', error.message, info.componentStack);
  }

  componentDidUpdate(prev: Props): void {
    if (this.state.error && prev.resetKey !== this.props.resetKey) this.setState({ error: null });
  }

  render() {
    if (this.state.error) {
      return this.props.fallback ?? (
        <div className="page-center" role="alert">
          <h1>Something went wrong</h1>
          <p>An unexpected error occurred while displaying this page.</p>
          <button type="button" className="btn btn-primary btn-md" onClick={() => this.setState({ error: null })}>Try again</button>
        </div>
      );
    }
    return this.props.children;
  }
}
