// App shell: hash-route switch, top bar with type-ahead, side navigation,
// and the always-on-top mini-player mode.
import React, { useEffect, useRef, useState } from 'react';
import { useRoute, navigate } from './router';
import { useApp, AppProvider } from './store';
import { FeedScreen } from './screens/FeedScreen';
import { SearchScreen } from './screens/SearchScreen';
import { WatchScreen } from './screens/WatchScreen';
import { ChannelScreen } from './screens/ChannelScreen';
import { PlaylistScreen, LocalPlaylistsHub } from './screens/PlaylistScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { SubscriptionsScreen } from './screens/SubscriptionsScreen';
import { DownloadsScreen, LocalPlayerScreen } from './screens/DownloadsScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { PlaybackHost } from './player/PlaybackHost';
import {
  ClockIcon, DownloadIcon, FireIcon, HistoryIcon, HomeIcon, MenuIcon, PlaylistIcon,
  PlayIcon, SearchIcon, SettingsIcon, SubsIcon, ThumbUpIcon,
} from './components/icons';

// Clicking the logo or Home navigates back to the feed. Coming from another
// page shows the feed already cached (no reload, no flicker); clicking it
// again while on Home is what asks for a fresh mix, exactly like YouTube.
function homeClick() {
  const hash = window.location.hash;
  const onHome = hash === '' || hash === '#/' || hash === '#/home';
  if (!onHome) { navigate('/home'); return; }
  window.dispatchEvent(new Event('indio-feed-refresh'));
}

// ---- top bar ------------------------------------------------------------------

function TopBar({ onMenu }: { onMenu(): void }) {
  const route = useRoute();
  const [text, setText] = useState(route.page === 'search' ? route.query : '');
  const [suggests, setSuggests] = useState<string[]>([]);
  const boxRef = useRef<HTMLDivElement>(null);
  const timerRef = useRef<number>(0);

  useEffect(() => {
    if (route.page === 'search') setText(route.query);
  }, [route.page, route.query]);

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      const target = e.target instanceof Node ? e.target : null;
      if (boxRef.current && !(target && boxRef.current.contains(target))) setSuggests([]);
    };
    window.addEventListener('mousedown', onDown);
    return () => window.removeEventListener('mousedown', onDown);
  }, []);

  const onChange = (value: string) => {
    setText(value);
    window.clearTimeout(timerRef.current);
    if (!value.trim()) { setSuggests([]); return; }
    timerRef.current = window.setTimeout(async () => {
      try { setSuggests(await window.indio.suggest(value)) } catch { setSuggests([]); }
    }, 220);
  };

  const go = (q: string) => {
    setSuggests([]);
    navigate(`/search?q=${encodeURIComponent(q.trim())}`);
  };

  return (
    <div className="topbar">
      <div className="tb-left mini-hide">
        <button className="icon-btn" onClick={onMenu} title="Menu"><MenuIcon size={22} /></button>
        <div className="brand" onClick={homeClick}>
          <span className="logo"><PlayIcon size={15} /></span> IndioTube
        </div>
      </div>
      <div className="search-wrap" ref={boxRef}>
        <div className="search-box">
          <input
            placeholder="Search videos, channels, playlists"
            value={text}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && text.trim()) go(text); }}
          />
          <button className="go" onClick={() => text.trim() && go(text)} title="Search"><SearchIcon size={18} /></button>
        </div>
        {suggests.length > 0 && (
          <div className="suggest-list">
            {suggests.map((s) => (
              <div key={s} onClick={() => { setText(s); go(s); }}><SearchIcon size={15} /> {s}</div>
            ))}
          </div>
        )}
      </div>
      <div className="tb-right mini-hide" />
    </div>
  );
}

// ---- side nav ------------------------------------------------------------------

function SideNav({ onClose }: { onClose(): void }) {
  const route = useRoute();
  const { lib } = useApp();
  const page = route.page;
  const Item = ({ to, icon, label, active, onClick }: { to: string; icon: React.ReactNode; label: string; active?: boolean; onClick?: () => void }) => (
    <div className={`item${(active ?? ('#' + to) === window.location.hash) ? ' active' : ''}`}
      onClick={() => { if (onClick) onClick(); else { navigate(to); onClose(); } }}>
      {icon} {label}
    </div>
  );
  return (
    <div className="sidenav mini-hide">
      <Item to="/home" icon={<HomeIcon size={20} />} label="Home" active={page === 'home' || page === 'watch'}
        onClick={homeClick} />
      <Item to="/trending" icon={<FireIcon size={20} />} label="Trending" active={page === 'trending'} />
      <Item to="/subscriptions" icon={<SubsIcon size={20} />} label="Subscriptions"
        active={lib?.subscriptions.some((s) => `#/channel/${s.channelId}` === window.location.hash)} />
      <div className="sep" />
      <Item to="/history" icon={<HistoryIcon size={20} />} label="History" active={page === 'history'} />
      <Item to="/watchlater" icon={<ClockIcon size={20} />} label="Watch later" active={page === 'watchlater'} />
      <Item to="/liked" icon={<ThumbUpIcon size={20} />} label="Liked videos" active={page === 'liked'} />
      <Item to="/playlists" icon={<PlaylistIcon size={20} />} label="Playlists" active={page === 'playlists' || page === 'playlist'} />
      <Item to="/downloads" icon={<DownloadIcon size={20} />} label="Downloads" active={page === 'downloads'} />
      <div className="sep" />
      <Item to="/settings" icon={<SettingsIcon size={20} />} label="Settings" active={page === 'settings'} />
      {lib && (
        <div style={{ padding: '18px 14px 6px', color: 'var(--text-dim)', fontSize: 11, lineHeight: 1.6 }}>
          {lib.library.history.length} watched · {lib.subscriptions.length} subscriptions<br />
          Profile: {lib.profileName}
        </div>
      )}
    </div>
  );
}

// ---- shell ---------------------------------------------------------------------

function Shell() {
  const route = useRoute();
  const [fs, setFs] = useState(false);
  const [navOpen, setNavOpen] = useState(true);
  const contentRef = useRef<HTMLDivElement>(null);
  // On a video the guide is an overlay drawer (YouTube behaviour): opening it
  // must not resize the player, so it floats above the content with a scrim.
  const overlayNav = route.page === 'watch' && navOpen;

  useEffect(() => {
    window.indio.window.isFullscreen().then(setFs).catch(() => undefined);
    return window.indio.window.onFullscreen(setFs);
  }, []);

  // Watching a video is an immersive view: collapse the side navigation so the
  // player and the Up next rail get the full width (the hamburger brings it
  // back without interrupting playback).
  useEffect(() => {
    if (route.page === 'watch') setNavOpen(false);
  }, [route.page]);

  // Opening a video always starts at the top of the page, even when the click
  // came from far down the feed.
  useEffect(() => {
    if (route.page === 'watch') contentRef.current?.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
  }, [route.page, route.id]);

  // A feed refresh (logo / Home clicked while on Home) brings the viewer back
  // to the top so the new mix is visible from its first row.
  useEffect(() => {
    const onRefresh = () => contentRef.current?.scrollTo({ top: 0, behavior: 'instant' as ScrollBehavior });
    window.addEventListener('indio-feed-refresh', onRefresh);
    return () => window.removeEventListener('indio-feed-refresh', onRefresh);
  }, []);

  // The player layer is a sibling of the shell, so fullscreen is flagged on
  // the document for it to react to.
  useEffect(() => {
    document.documentElement.dataset.fs = fs ? '1' : '';
  }, [fs]);

  let screen: React.ReactNode;
  switch (route.page) {
    case 'watch': screen = <WatchScreen videoId={route.id} />; break;
    case 'local': screen = <LocalPlayerScreen url={decodeURIComponent(route.id)} title="Downloaded video" />; break;
    case 'search': screen = <SearchScreen />; break;
    case 'channel': screen = <ChannelScreen channelId={route.id} />; break;
    case 'playlist': screen = <PlaylistScreen kind={route.id === 'local' ? 'local' : 'yt'} id={route.mode} />; break;
    case 'playlists': screen = <LocalPlaylistsHub />; break;
    case 'history': screen = <LibraryScreen kind="history" />; break;
    case 'watchlater': screen = <LibraryScreen kind="watchlater" />; break;
    case 'liked': screen = <LibraryScreen kind="liked" />; break;
    case 'subscriptions': screen = <SubscriptionsScreen />; break;
    case 'downloads': screen = <DownloadsScreen />; break;
    case 'settings': screen = <SettingsScreen />; break;
    case 'trending': screen = <FeedScreen mode="trending" />; break;
    default: screen = <FeedScreen mode="home" />;
  }

  return (
    <div className={`shell${fs ? ' fs' : ''}`} style={{ flexDirection: 'column' }}>
      <TopBar onMenu={() => setNavOpen((o) => !o)} />
      <div className="main">
        {navOpen && !overlayNav && <SideNav onClose={() => undefined} />}
        <div className="content" ref={contentRef}>{screen}</div>
        {overlayNav && (
          <>
            <div className="nav-scrim" onClick={() => setNavOpen(false)} />
            <div className="nav-drawer" role="dialog" aria-label="Navigation">
              <SideNav onClose={() => setNavOpen(false)} />
            </div>
          </>
        )}
      </div>
    </div>
  );
}

export default function App() {
  return (
    <AppProvider>
      <PlaybackHost>
        <Shell />
      </PlaybackHost>
    </AppProvider>
  );
}
