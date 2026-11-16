import { useCallback, useEffect, useState } from 'react';
import { CastReceiver } from './components/CastReceiver';
import { InstallPage } from './components/InstallPage';

function normalizePath(pathname: string): string {
  const trimmed = pathname.replace(/\/+$/, '');
  return trimmed || '/';
}

export default function App() {
  const [pathname, setPathname] = useState<string>(() => normalizePath(window.location.pathname));

  useEffect(() => {
    const onPopState = () => {
      setPathname(normalizePath(window.location.pathname));
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, []);

  const navigate = useCallback((nextPath: string) => {
    const normalized = normalizePath(nextPath);
    if (normalizePath(window.location.pathname) !== normalized) {
      window.history.pushState(null, '', normalized);
    }
    setPathname(normalized);
  }, []);

  if (pathname === '/install') {
    return <InstallPage onNavigateHome={() => navigate('/')} />;
  }

  return <CastReceiver onOpenInstall={() => navigate('/install')} />;
}
