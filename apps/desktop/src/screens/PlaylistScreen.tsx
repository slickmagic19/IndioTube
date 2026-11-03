// Playlist page for both YouTube playlists (fetched) and local playlists
// (from the library). Local ones support rename, delete and removing rows.
import React, { useEffect, useRef, useState } from 'react';
import type { PlaylistPage, VideoItem } from '../types';
import { formatDuration } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { VideoCard, openVideo } from '../components/VideoCard';
import { Menu, Spinner, Thumb } from '../components/ui';
import { GridSkeleton } from '../components/Skeletons';
import { MoreIcon, PlusIcon, TrashIcon } from '../components/icons';

export function PlaylistScreen({ kind, id }: { kind: 'yt' | 'local'; id: string }) {
  const { lib, op, toast } = useApp();
  const [page, setPage] = useState<PlaylistPage | null>(null);
  const [loading, setLoading] = useState(kind === 'yt');
  const [error, setError] = useState<string | null>(null);
  const [menuAnchor, setMenuAnchor] = useState<DOMRect | null>(null);
  const [renaming, setRenaming] = useState(false);
  const [newName, setNewName] = useState('');
  const sentinel = useRef<HTMLDivElement>(null);

  const local = kind === 'local' ? lib?.library.playlists.find((p) => p.id === id) : undefined;

  useEffect(() => {
    if (kind !== 'yt') return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    window.indio.playlist(id)
      .then((p) => { if (!cancelled) setPage(p); })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [kind, id]);

  useEffect(() => {
    if (kind !== 'yt' || !page?.continuation) return;
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver((entries) => {
      if (!entries[0].isIntersecting) return;
      window.indio.playlistCont(page.continuation!)
        .then((p) => setPage((cur) => cur && ({
          ...cur,
          videos: [...cur.videos, ...p.videos],
          continuation: p.continuation === cur.continuation ? null : p.continuation,
        })))
        .catch(() => setPage((cur) => cur && { ...cur, continuation: null }));
    }, { rootMargin: '500px' });
    io.observe(el);
    return () => io.disconnect();
  }, [kind, page]);

  if (kind === 'local') {
    if (!local) {
      return <div className="state"><div>This playlist no longer exists.</div><button onClick={() => navigate('/playlists')}>All playlists</button></div>;
    }
    return (
      <div>
        <div className="section-head" style={{ marginTop: 4 }}>
          <div>
            <h2 style={{ margin: 0 }}>{local.name}</h2>
            <div className="sub">{local.videos.length} videos</div>
          </div>
          <button className="icon-btn" style={{ marginLeft: 'auto' }} onClick={(e) => setMenuAnchor(e.currentTarget.getBoundingClientRect())}>
            <MoreIcon size={18} />
          </button>
        </div>
        {menuAnchor && (
          <Menu anchor={menuAnchor} onClose={() => setMenuAnchor(null)} width={220}>
            {renaming ? (
              <div style={{ padding: 8 }}>
                <input className="text-input" autoFocus style={{ width: '100%' }} value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  onKeyDown={async (e) => {
                    if (e.key === 'Enter') {
                      await op('renamePlaylist', local.id, newName);
                      toast('Playlist renamed');
                      setMenuAnchor(null);
                      setRenaming(false);
                    }
                  }} />
              </div>
            ) : (
              <>
                <button onClick={() => { setRenaming(true); setNewName(local.name); }}>Rename playlist</button>
                <button onClick={async () => {
                  await op('deletePlaylist', local.id);
                  toast('Playlist deleted');
                  setMenuAnchor(null);
                  navigate('/playlists');
                }}><TrashIcon size={16} /> Delete playlist</button>
              </>
            )}
          </Menu>
        )}
        {!local.videos.length ? (
          <div className="state">
            <div>Empty for now — use “Save” on any video to add it here.</div>
          </div>
        ) : (
          <div className="grid">{local.videos.map((v, i) => <VideoCard key={`${v.videoId}-${i}`} video={v} />)}</div>
        )}
      </div>
    );
  }

  if (loading) return <GridSkeleton count={12} />;
  if (error || !page) {
    return <div className="state"><div>Playlist unavailable: {error || 'not found'}</div></div>;
  }
  return (
    <div>
      <div className="section-head">
        <div>
          <h2 style={{ margin: 0 }}>{page.title}</h2>
          <div className="sub">{[page.author && `By ${page.author}`, page.videoCountText].filter(Boolean).join(' • ')}</div>
        </div>
      </div>
      <div className="grid">
        {page.videos.map((v, i) => <VideoCard key={`${v.videoId}-${i}`} video={v} />)}
      </div>
      <div ref={sentinel} style={{ height: 4 }} />
    </div>
  );
}

export function LocalPlaylistsHub() {
  const { lib, op, toast } = useApp();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const playlists = lib?.library.playlists || [];
  const later = lib?.library.watchLater || [];
  const liked = lib?.library.liked || [];

  const create = async () => {
    const clean = name.trim();
    if (!clean) return;
    const pl = await op<any>('createPlaylist', clean);
    setName('');
    setCreating(false);
    navigate(`/playlist/local/${pl.id}`);
  };

  return (
    <div>
      <div className="section-head">
        <h2>Your playlists</h2>
        <button className="btn" onClick={() => setCreating(true)}><PlusIcon size={16} /> New playlist</button>
      </div>
      {creating && (
        <div style={{ display: 'flex', gap: 8, marginBottom: 16, maxWidth: 420 }}>
          <input className="text-input" autoFocus style={{ flex: 1 }} placeholder="Playlist name" value={name}
            onChange={(e) => setName(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') create(); }} />
          <button className="btn" onClick={create}>Create</button>
          <button className="btn ghost" onClick={() => setCreating(false)}>Cancel</button>
        </div>
      )}
      <div className="grid compact">
        <PlaylistTile title="Watch later" count={later.length} first={later[0]} onClick={() => navigate('/watchlater')} />
        <PlaylistTile title="Liked videos" count={liked.length} first={liked[0]} onClick={() => navigate('/liked')} />
        {playlists.map((p) => (
          <PlaylistTile key={p.id} title={p.name} count={p.videos.length} first={p.videos[0]}
            onClick={() => navigate(`/playlist/local/${p.id}`)} />
        ))}
      </div>
    </div>
  );
}

function PlaylistTile({ title, count, first, onClick }: {
  title: string; count: number; first?: VideoItem; onClick(): void;
}) {
  return (
    <div className="card" onClick={onClick}>
      <div className="thumb-wrap" style={{ background: '#000' }}>
        {first?.thumbnail
          ? <img src={first.thumbnail} alt="" loading="lazy" />
          : <div style={{ width: '100%', height: '100%', background: 'var(--chip)' }} />}
        <span className="duration">{count} videos</span>
      </div>
      <div className="title" style={{ marginTop: 8 }}>{title}</div>
    </div>
  );
}
