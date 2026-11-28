
import { useTranslation } from 'react-i18next';

/** The page title row (report §1, `.header-nav`). */
export function PageHeader({ title }: { title: string }) {
  const { t } = useTranslation();
  return (
    <div className="mx-(--body-gutter) pt-8">
      <h1 className="type-header-emphasized">{t(title)}</h1>
    </div>
  );
}
