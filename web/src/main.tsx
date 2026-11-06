import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import './styles.css';
import { ToastProvider } from './components/ui';
import { Help, Home, LinkPage, Privacy, SiteLayout, Terms } from './site/Site';
import { AdminRoot } from './admin/Admin';
import { Dashboard, DeviceDetail, Devices } from './admin/Devices';
import { ProfileDetail, Profiles } from './admin/Profiles';
import { LauncherEdit, Launchers, SettingsPage } from './admin/Settings';
import { Account, Admins, Audit, Messages, Releases } from './admin/System';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ToastProvider>
      <BrowserRouter>
        <Routes>
          <Route element={<SiteLayout />}>
            <Route index element={<Home />} />
            <Route path="link" element={<LinkPage />} />
            <Route path="pomoc" element={<Help />} />
            <Route path="uslovi" element={<Terms />} />
            <Route path="privatnost" element={<Privacy />} />
          </Route>
          <Route path="admin" element={<AdminRoot />}>
            <Route index element={<Dashboard />} />
            <Route path="devices" element={<Devices />} />
            <Route path="devices/:id" element={<DeviceDetail />} />
            <Route path="profiles" element={<Profiles />} />
            <Route path="profiles/:id" element={<ProfileDetail />} />
            <Route path="launchers" element={<Launchers />} />
            <Route path="launchers/:id" element={<LauncherEdit />} />
            <Route path="messages" element={<Messages />} />
            <Route path="settings" element={<SettingsPage />} />
            <Route path="releases" element={<Releases />} />
            <Route path="admins" element={<Admins />} />
            <Route path="audit" element={<Audit />} />
            <Route path="account" element={<Account />} />
          </Route>
          <Route path="*" element={<SiteLayout />}>
            <Route path="*" element={<div className="empty"><h1>Stranica ne postoji</h1><a href="/">Početna</a></div>} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ToastProvider>
  </StrictMode>,
);
