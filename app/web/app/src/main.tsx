import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { queryClient } from '@/lib/queryClient';
import { routes } from '@/routes';
import { i18nReady } from '@/i18n';
import { installIconLayerBounce } from '@/motion/iconBounce';
import '@/styles/global.css';

const router = createBrowserRouter(routes);

void i18nReady.then(() => {
  installIconLayerBounce();
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  );
});
