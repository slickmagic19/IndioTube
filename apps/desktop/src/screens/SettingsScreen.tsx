// Settings: profile, playback defaults, downloads, appearance, and the
// phone-sync entry point.
import React, { useState } from 'react';
import { useApp } from '../store';
import { SyncModal } from '../components/SyncModal';
import { QrIcon } from '../components/icons';
import { SPONSOR_CATEGORIES } from '../player/sponsorLogic';

export function SettingsScreen() {
  const { settings, saveSettings, lib, op, toast } = useApp();
  const [syncOpen, setSyncOpen] = useState(false);
  const [profile, setProfile] = useState(lib?.profileName || '');

  if (!settings) return <div className="state">Loading settings…</div>;

  const set = (patch: Partial<typeof settings>) => saveSettings(patch as any);

  const toggleCategory = (id: string, on: boolean) => {
    const next = new Set(settings.sponsorBlockCategories || []);
    if (on) next.add(id); else next.delete(id);
    set({ sponsorBlockCategories: SPONSOR_CATEGORIES.map((c) => c.id).filter((x) => next.has(x)) });
  };

  return (
    <div className="settings">
      <div className="section-head"><h2>Settings</h2></div>

      <h2>Profile</h2>
      <div className="field">
        <div className="label"><b>Device name</b><span>Shown on your phone while syncing</span></div>
        <div style={{ display: 'flex', gap: 8 }}>
          <input className="text-input" value={profile} onChange={(e) => setProfile(e.target.value)} />
          <button className="btn ghost" onClick={() => { op('setProfileName', profile); toast('Name saved'); }}>Save</button>
        </div>
      </div>
      <div className="field">
        <div className="label"><b>Sync with phone</b><span>Pair over your Wi-Fi network — no account, no cloud</span></div>
        <button className="btn" onClick={() => setSyncOpen(true)}><QrIcon size={16} /> Open sync</button>
      </div>

      <h2>Playback</h2>
      <div className="field">
        <div className="label"><b>Autoplay up next</b></div>
        <button className={`switch${settings.autoplay ? ' on' : ''}`} onClick={() => set({ autoplay: !settings.autoplay })}><i /></button>
      </div>
      <div className="field">
        <div className="label"><b>Default quality</b><span>Applies to the next video you open</span></div>
        <select value={settings.defaultQuality ?? 'auto'} onChange={(e) => {
          const v = e.target.value;
          set({ defaultQuality: v === 'auto' ? null : Number(v) });
        }}>
          <option value="auto">Auto — 720p when available</option>
          {[1080, 720, 480, 360].map((h) => <option key={h} value={h}>{h}p</option>)}
        </select>
      </div>
      <div className="field">
        <div className="label"><b>Captions on by default</b></div>
        <button className={`switch${settings.captionsEnabled ? ' on' : ''}`}
          onClick={() => set({ captionsEnabled: !settings.captionsEnabled })}><i /></button>
      </div>
      <div className="field">
        <div className="label"><b>SponsorBlock</b><span>Community-submitted sponsor, intro and self-promo segments</span></div>
        <button className={`switch${settings.sponsorBlock ? ' on' : ''}`}
          onClick={() => set({ sponsorBlock: !settings.sponsorBlock })}><i /></button>
      </div>
      {settings.sponsorBlock && (
        <>
          <div className="field">
            <div className="label"><b>Skip segments automatically</b><span>Off shows a Skip button in the player instead</span></div>
            <button className={`switch${settings.sponsorBlockAutoSkip ? ' on' : ''}`}
              onClick={() => set({ sponsorBlockAutoSkip: !settings.sponsorBlockAutoSkip })}><i /></button>
          </div>
          <div className="sb-cats">
            <div className="sb-cats-head">Categories to skip</div>
            {SPONSOR_CATEGORIES.map((c) => {
              const on = (settings.sponsorBlockCategories || []).includes(c.id);
              return (
                <label key={c.id} className={`check${on ? ' on' : ''}`}>
                  <input type="checkbox" checked={on} onChange={(e) => toggleCategory(c.id, e.target.checked)} />
                  <span className="check-text"><b>{c.label}</b><small>{c.description}</small></span>
                </label>
              );
            })}
          </div>
        </>
      )}

      <h2>Downloads</h2>
      <div className="field">
        <div className="label"><b>Folder</b><span style={{ wordBreak: 'break-all' }}>{settings.downloadDir}</span></div>
        <button className="btn ghost" onClick={async () => {
          const { dir } = await window.indio.downloads.pickDir();
          set({ downloadDir: dir });
        }}>Change</button>
      </div>
      <div className="field">
        <div className="label"><b>Download quality</b></div>
        <select value={settings.downloadHeight} onChange={(e) => set({ downloadHeight: Number(e.target.value) })}>
          {[2160, 1440, 1080, 720, 480, 360].map((h) => <option key={h} value={h}>{h}p</option>)}
        </select>
      </div>

      <h2>Appearance</h2>
      <div className="field">
        <div className="label"><b>Theme</b></div>
        <select value={settings.theme} onChange={(e) => set({ theme: e.target.value as 'dark' | 'light' })}>
          <option value="dark">Dark</option>
          <option value="light">Light</option>
        </select>
      </div>
      <div className="field">
        <div className="label"><b>Pause watch history</b><span>This desktop profile only</span></div>
        <button className={`switch${lib?.library.historyPaused ? ' on' : ''}`}
          onClick={() => op('setHistoryPaused', !lib?.library.historyPaused)}><i /></button>
      </div>

      <h2>Storage</h2>
      <div className="field">
        <div className="label"><b>Clear search history</b><span>{lib?.library.searches.length || 0} saved searches</span></div>
        <button className="btn ghost" onClick={() => { op('clearSearches'); toast('Search history cleared'); }}>Clear</button>
      </div>
      <div className="field">
        <div className="label"><b>Clear watch history</b><span>{lib?.library.history.length || 0} videos</span></div>
        <button className="btn ghost" onClick={() => { op('clearHistory'); toast('Watch history cleared'); }}>Clear</button>
      </div>

      {syncOpen && <SyncModal onClose={() => setSyncOpen(false)} />}
    </div>
  );
}
