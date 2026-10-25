import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { tokenStore } from '../auth/tokenStore';

afterEach(() => {
  cleanup();
  tokenStore.clear();
  tokenStore.reset();
  sessionStorage.clear();
});
