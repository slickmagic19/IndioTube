// Search results with the YouTube filter menu (type / sort / upload date /
// duration). The filter integers match the Android app's protobuf params.
import React, { useEffect, useRef, useState } from 'react';
import type { SearchEntry, SearchResults } from '../types';
import { navigate, useRoute } from '../router';
import { VideoCard, openVideo } from '../components/VideoCard';
import { Avatar, Menu, MenuHead, Thumb } from '../components/ui';
import { ListSkeleton } from '../components/Skeletons';
import { SettingsIcon } from '../components/icons';
import { useApp } from '../store';
import { formatDuration } from '../types';
import { withSearchRetry } from '../searchLogic';

interface Filters { type?: number | null; sort?: number | null; uploadDate?: number | null; duration?: number | null }

const TYPE_OPTS: [number | null, string][] = [[null, 'All'], [1, 'Videos'], [2, 'Channels'], [3, 'Playlists']];
const SORT_OPTS: [number | null, string][] = [[null, 'Relevance'], [1, 'Rating'], [2, 'Upload date'], [3, 'View count']];
const DATE_OPTS: [number | null, string][] = [[null, 'Any'], [1, 'Last hour'], [2, 'Today'], [3, 'This week'], [4, 'This month'], [5, 'This year']];
const DUR_OPTS: [number | null, string][] = [[null, 'Any'], [1, 'Under 4 min'], [3, '4–20 min'], [2, 'Over 20 min']];

export function SearchScreen() {
  const route = useRoute();
  const query = route.query;
  const { op } = useApp();
  const [results, setResults] = useState<SearchResults | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [filters, setFilters] = useState<Filters>({});
  const [filterAnchor, setFilterAnchor] = useState<DOMRect | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const sentinel = useRef<HTMLDivElement>(null);
  const activeFilters = Object.values(filters).filter((v) => v != null).length;

  useEffect(() => {
    if (!query) {
      setResults(null);
      setError(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    // A failed query must never leave the previous query's rows on screen:
    // that is what made a flaky request look like "search returned nothing"
    // or, worse, the wrong results.
    setResults(null);
    op('noteSearch', query).catch(() => undefined);
    withSearchRetry(() => window.indio.search(query, filters as any))
      .then((r) => { if (!cancelled) setResults(r); })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [query, filters, attempt]);

  useEffect(() => {
    const el = sentinel.current;
    if (!el || !results?.continuation || loading) return;
    const io = new IntersectionObserver((entries) => {
      if (!entries[0].isIntersecting || loadingMore) return;
      setLoadingMore(true);
      withSearchRetry(() => window.indio.searchCont(results.continuation!))
        .then((r) => setResults((cur) => cur && ({
          entries: [...cur.entries, ...r.entries],
          continuation: r.continuation === cur.continuation ? null : r.continuation,
          restricted: cur.restricted,
        })))
        .catch(() => setResults((cur) => cur && { ...cur, continuation: null }))
        .finally(() => setLoadingMore(false));
    }, { rootMargin: '500px' });
    io.observe(el);
    return () => io.disconnect();
  }, [results, loading, loadingMore]);

  const apply = (patch: Filters) => {
    setFilters((f) => ({ ...f, ...patch }));
    setFilterAnchor(null);
  };

  if (!query) {
    return <div className="state"><div>Search YouTube — try a channel, a song, or a topic.</div></div>;
  }
  if (loading) return <ListSkeleton count={6} />;
  if (error && !results) {
    return (
      <div className="state">
        <div>Search failed: {error}</div>
        <div>Check your connection, then try again.</div>
        <button onClick={() => setAttempt((n) => n + 1)}>Retry</button>
      </div>
    );
  }

  const entries = results?.entries || [];
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <h2 style={{ margin: 0, fontSize: 17 }}>Results for “{query}”</h2>
        <button className="btn ghost" style={{ marginLeft: 'auto' }} onClick={(e) => setFilterAnchor(e.currentTarget.getBoundingClientRect())}>
          <SettingsIcon size={16} /> Filters{activeFilters ? ` (${activeFilters})` : ''}
        </button>
      </div>
      {filterAnchor && (
        <Menu anchor={filterAnchor} onClose={() => setFilterAnchor(null)} width={250}>
          <MenuHead>Type</MenuHead>
          {TYPE_OPTS.map(([v, label]) => (
            <button key={label} onClick={() => apply({ type: v })} className={filters.type === v ? 'q-item sel' : ''}>
              {filters.type === v ? '✓ ' : ''}{label}
            </button>
          ))}
          <MenuHead>Sort by</MenuHead>
          {SORT_OPTS.map(([v, label]) => (
            <button key={label} onClick={() => apply({ sort: v })}>{filters.sort === v ? '✓ ' : ''}{label}</button>
          ))}
          <MenuHead>Upload date</MenuHead>
          {DATE_OPTS.map(([v, label]) => (
            <button key={label} onClick={() => apply({ uploadDate: v })}>{filters.uploadDate === v ? '✓ ' : ''}{label}</button>
          ))}
          <MenuHead>Duration</MenuHead>
          {DUR_OPTS.map(([v, label]) => (
            <button key={label} onClick={() => apply({ duration: v })}>{filters.duration === v ? '✓ ' : ''}{label}</button>
          ))}
        </Menu>
      )}
      {!entries.length ? (
        <div className="state">
          <div>{results?.restricted ? 'YouTube age-gated this search anonymously.' : 'No results.'}</div>
        </div>
      ) : (
        entries.map((entry, i) => <ResultRow key={i} entry={entry} />)
      )}
      <div ref={sentinel} style={{ height: 4 }} />
      {loadingMore && <ListSkeleton count={2} />}
    </div>
  );
}

function ResultRow({ entry }: { entry: SearchEntry }) {
  if (entry.kind === 'video') {
    // Search videos render as wide rows, like YouTube desktop.
    const v = entry.video;
    return (
      <div className="list-row" style={{ padding: '10px 6px' }} onClick={() => openVideo(v)}>
        <div className="thumb" style={{ width: 220, minWidth: 220 }}>
          <Thumb src={v.thumbnail} alt={v.title} live={v.isLive} duration={v.isLive ? undefined : formatDuration(v.durationSec)} members={v.membersOnly} />
        </div>
        <div className="grow">
          <div className="t" style={{ whiteSpace: 'normal', fontSize: 15, fontWeight: 600 }}>{v.title}</div>
          <div className="s">{[v.viewsText, v.publishedText].filter(Boolean).join(' • ')}</div>
          <div className="s" style={{ display: 'flex', alignItems: 'center', marginTop: 6 }}>
            <span
              className="channel-link"
              role="link"
              tabIndex={0}
              title={v.channelName ? `Open ${v.channelName}` : undefined}
              onClick={(e) => { e.stopPropagation(); if (v.channelId) navigate(`/channel/${v.channelId}`); }}
              onKeyDown={(e) => {
                if (e.key !== 'Enter' && e.key !== ' ') return;
                e.stopPropagation();
                e.preventDefault();
                if (v.channelId) navigate(`/channel/${v.channelId}`);
              }}
            >
              <Avatar name={v.channelName} src={v.channelAvatar} size={24} /> {v.channelName}
            </span>
          </div>
          <div className="s" style={{ marginTop: 6 }}>{(v as any).descriptionSnippet || ''}</div>
        </div>
      </div>
    );
  }
  if (entry.kind === 'channel') {
    const c = entry.channel;
    return (
      <div className="list-row" onClick={() => navigate(`/channel/${c.channelId}`)}>
        <Avatar name={c.name} src={c.avatar} size={88} />
        <div className="grow">
          <div className="t" style={{ fontSize: 15 }}>{c.name}</div>
          <div className="s">{[c.subscribersText, c.videoCountText].filter(Boolean).join(' • ')}</div>
          {c.description && <div className="s" style={{ whiteSpace: 'normal', marginTop: 4 }}>{c.description}</div>}
        </div>
      </div>
    );
  }
  const p = entry.playlist;
  return (
    <div className="list-row" onClick={() => navigate(`/playlist/yt/${p.playlistId}`)}>
      <div className="thumb" style={{ width: 220, minWidth: 220 }}>
        <Thumb src={p.thumbnail} alt={p.title} />
      </div>
      <div className="grow">
        <div className="t" style={{ fontSize: 15, whiteSpace: 'normal' }}>{p.title}</div>
        <div className="s">{p.videoCountText || ''} • by {p.channelName || 'YouTube'}</div>
      </div>
    </div>
  );
}
