import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';
import { useTranslation } from 'react-i18next';

export default function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title="Page not found" />
      <p className="mx-(--body-gutter) my-2 type-title-3 text-secondary">
        {t('This page doesn’t exist.')} <PrefetchLink to="/home" className="text-key hover:underline">{t('Go to Home')}</PrefetchLink>
      </p>
    </>
  );
}
