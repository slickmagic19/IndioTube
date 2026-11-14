// The watch page: the player lives in the persistent playback host and is
// positioned over the slot below, followed by title/actions, the channel row,
// description with chapters, comments and the up-next rail.
import React, { useEffect, useMemo, useState } from 'react';
import type { CommentItem, VideoItem, WatchData } from '../types';
import { formatDuration } from '../types';
import { navigate } from '../router';
import { useApp } from '../store';
import { usePlayback } from '../player/PlaybackHost';
import { RelatedCard, downloadVideo, openVideo } from '../components/VideoCard';
import { Avatar, Menu, Verified } from '../components/ui';
import { WatchSkeleton } from '../components/Skeletons';
import { peekVideo } from '../videoHints';
import { warmPage } from '../player/warmStreams';
import { ClockIcon, DownloadIcon, PanelRightIcon, PlaylistIcon, ThumbDownIcon, ThumbUpIcon } from '../components/icons';
import { PlaylistPicker } from '../components/VideoCard';

export function WatchScreen({ videoId }: { videoId: string }) {
  const { lib, op, toast, settings, saveSettings } = useApp();
  const playback = usePlayback();
  const [watch, setWatch] = useState<WatchData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [comments, setComments] = useState<CommentItem[]>([]);
  const [commentsCont, setCommentsCont] = useState<string | null>(null);
  const [related, setRelated] = useState<VideoItem[]>([]);
  const [relatedToken, setRelatedToken] = useState<string | null>(null);
  const [moreRelated, setMoreRelated] = useState(false);
  const [descOpen, setDescOpen] = useState(false);
  const [saveAnchor, setSaveAnchor] = useState<DOMRect | null>(null);

  // The card that was clicked already showed the title, the channel and the
  // length, so the page starts with those instead of a blank skeleton while
  // the metadata round trip is still in the air.
  const known = useMemo(() => peekVideo(videoId), [videoId]);
  // YouTube's multi-owner uploads ("Vox and 2 more") come back on the watch
  // payload without a channel at all; the card has one, so the row never
  // collapses into an empty circle with nothing beside it.
  const channel = {
    name: watch?.channelName || known?.channelName || '',
    id: watch?.channelId || known?.channelId || '',
    avatar: watch?.channelAvatar ?? known?.channelAvatar ?? null,
    verified: Boolean(watch?.channelVerified),
  };

  const video: VideoItem = useMemo(() => {
    if (watch) {
      return {
        videoId,
        title: watch.title,
        channelName: channel.name,
        channelId: channel.id,
        channelAvatar: channel.avatar,
        thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
        durationSec: 0,
        viewsText: watch.viewsText,
        publishedText: watch.publishedText,
        isLive: watch.isLive,
        membersOnly: watch.membersOnly,
      };
    }
    return known ?? {
      videoId, title: 'Loading…', durationSec: 0, isLive: false, membersOnly: false,
    };
  }, [watch, known]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let cancelled = false;
    setWatch(null);
    setError(null);
    setComments([]);
    setRelated([]);
    setRelatedToken(null);
    setDescOpen(false);
    // Hand the video to the shared player straight away (it keeps playing
    // across routes) and warm the yt-dlp ladder in parallel with the metadata.
    const stub: VideoItem = peekVideo(videoId) ?? {
      videoId, title: 'Loading…', durationSec: 0, isLive: false, membersOnly: false,
      thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    };
    if (playback.video?.videoId !== videoId) playback.play(stub);
    window.indio.streams.qualities(videoId).catch(() => undefined);
    window.indio.watch(videoId)
      .then((w) => {
        if (cancelled) return;
        setWatch(w);
        setRelated(w.related);
        setRelatedToken(w.relatedToken || null);
        // The first couple of up-next videos are what this viewer is likely to
        // play next, so the shell is told to have them ready while they watch.
        warmPage(w.related, 3);
        const full: VideoItem = {
          videoId,
          title: w.title,
          channelName: w.channelName,
          channelId: w.channelId,
          channelAvatar: w.channelAvatar,
          thumbnail: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
          durationSec: 0,
          viewsText: w.viewsText,
          publishedText: w.publishedText,
          isLive: w.isLive,
          membersOnly: w.membersOnly,
        };
        playback.setWatchData(w, full);
        if (w.commentsToken) {
          window.indio.comments(w.commentsToken)
            .then((page) => {
              if (cancelled) return;
              setComments(page.comments);
              setCommentsCont(page.continuation);
            })
            .catch(() => undefined);
        }
      })
      .catch((e) => { if (!cancelled) setError(String(e?.message || e)); });
    return () => { cancelled = true; };
  }, [videoId]); // eslint-disable-line react-hooks/exhaustive-deps

  const liked = Boolean(lib?.library.liked.some((v) => v.videoId === videoId));
  const later = Boolean(lib?.library.watchLater.some((v) => v.videoId === videoId));
  const subscribed = Boolean(channel.id && lib?.subscriptions.some((s) => s.channelId === channel.id));

  const loadMoreComments = async () => {
    if (!commentsCont) return;
    const page = await window.indio.comments(commentsCont).catch(() => null);
    if (page) {
      setComments((c) => [...c, ...page.comments]);
      setCommentsCont(page.continuation === commentsCont ? null : page.continuation);
    }
  };

  const loadMoreRelated = async () => {
    if (!relatedToken || moreRelated) return;
    setMoreRelated(true);
    try {
      const page = await window.indio.watchMore(relatedToken);
      const known = new Set(related.map((v) => v.videoId));
      const fresh = page.related.filter((v) => !known.has(v.videoId));
      if (fresh.length) setRelated((cur) => [...cur, ...fresh]);
      const next = page.continuation && page.continuation !== relatedToken ? page.continuation : null;
      setRelatedToken(next);
      if (!next) toast('No more recommendations');
    } catch (e: any) {
      toast(String(e?.message || e));
    } finally {
      setMoreRelated(false);
    }
  };

  if (error) {
    return (
      <div className="state">
        <div>Couldn't open this video: {error}</div>
        <button onClick={() => navigate('/home')}>Back to Home</button>
      </div>
    );
  }
  if (!watch) {
    // The player is handed its slot here, not only in the real layout: it can
    // start streaming (and be seen doing it) while the metadata is still in
    // flight. The title comes from the card that was clicked.
    return <WatchSkeleton slotRef={playback.registerSlot} video={video} />;
  }

  // The "Up next" rail is toggled from its own header (and from the title row
  // while it is hidden); the choice is remembered across videos and restarts.
  const railHidden = Boolean(settings?.sidebarCollapsed);

  return (
    <div className={`watch-layout${settings?.theater ? ' theater' : ''} ${
      railHidden ? ' collapsed' : ''
    }`}>
      <div className="watch-main">
        {/* The persistent player covers this slot; it keeps playing when the
            user leaves this page and becomes the floating mini player. */}
        <div className="player-slot" ref={playback.registerSlot} />
        <div className="watch-title-row">
          <h1 className="watch-title">{watch.title}</h1>
          {railHidden && (
            <button className="rail-toggle" onClick={() => saveSettings({ sidebarCollapsed: false })}
              title="Show related videos" aria-label="Show related videos">
              <PanelRightIcon size={18} /> Related videos
            </button>
          )}
        </div>

        <div className="watch-bar">
          <div className="watch-channel" style={{ margin: 0 }}>
            <div className="avatar" onClick={() => channel.id && navigate(`/channel/${channel.id}`)} style={{ cursor: 'pointer' }}>
              <Avatar name={channel.name} src={channel.avatar} size={40} />
            </div>
            <div>
              <div className="name" onClick={() => channel.id && navigate(`/channel/${channel.id}`)}>
                {channel.name}
                {channel.verified ? <Verified size={13} /> : null}
              </div>
              <div className="subs">{watch.subscribersText || ''}</div>
            </div>
            {channel.id && (
              <button
                className={`pill${subscribed ? ' subbed' : ''}`}
                style={{ marginLeft: 14 }}
                onClick={() => {
                  if (subscribed) op('unsubscribe', channel.id);
                  else op('subscribe', { channelId: channel.id, name: channel.name, avatar: channel.avatar });
                }}
              >
                {subscribed ? 'Subscribed' : 'Subscribe'}
              </button>
            )}
          </div>

          <div className="pill-row">
            <div className="pill split">
              <button onClick={() => op('toggleLike', video).then(() => toast(liked ? 'Removed from Liked' : 'Liked'))}>
                <ThumbUpIcon size={18} /> {watch.likesText || (liked ? 'Liked' : 'Like')}
              </button>
              <div className="div" />
              <button onClick={() => toast('Disliked — fewer videos like this on Home')}>
                <ThumbDownIcon size={18} />
              </button>
            </div>
            <button className={`pill${later ? ' red' : ''}`} onClick={() => {
              op('toggleWatchLater', video).then(() => toast(later ? 'Removed from Watch later' : 'Saved to Watch later'));
            }} title={later ? 'Remove from Watch later' : 'Save to Watch later'}>
              <ClockIcon size={18} /> {later ? 'In Watch later' : 'Watch later'}
            </button>
            <button className="pill" onClick={(e) => setSaveAnchor(e.currentTarget.getBoundingClientRect())} title="Save to playlist">
              <PlaylistIcon size={18} /> Save
            </button>
            {!watch.membersOnly && !watch.isLive && (
              <button className="pill" onClick={() => downloadVideo(video, settings?.downloadHeight ?? 720, toast)} title="Download">
                <DownloadIcon size={18} /> Download
              </button>
            )}
          </div>
        </div>

        {saveAnchor && (
          <Menu anchor={saveAnchor} onClose={() => setSaveAnchor(null)} width={240}>
            <PlaylistPicker video={video} onClose={() => setSaveAnchor(null)} />
          </Menu>
        )}

        <div className="desc" onClick={() => setDescOpen((o) => !o)} style={{ cursor: 'pointer' }}>
          <b>{[watch.viewsText, watch.publishedText].filter(Boolean).join(' • ')}</b>
          {watch.description && (
            <div style={{ marginTop: 8 }}>
              {descOpen ? watch.description : `${watch.description.slice(0, 140)}${watch.description.length > 140 ? '…' : ''}`}
              {watch.description.length > 140 && <span className="more"> More</span>}
            </div>
          )}
        </div>

        {watch.chapters.length > 1 && (
          <div className="chapters-mini">
            {watch.chapters.map((c, i) => (
              <button key={i} onClick={() => {
                const v = document.querySelector('video') as HTMLVideoElement;
                if (v) v.currentTime = c.startMs / 1000;
              }}>
                <span style={{ color: 'var(--text-dim)', fontVariantNumeric: 'tabular-nums' }}>
                  {formatDuration(Math.floor(c.startMs / 1000))}
                </span>
                {c.title}
              </button>
            ))}
          </div>
        )}

        {comments.length > 0 && (
          <div className="comments">
            <h3>{watch.commentsCountText || `${comments.length}+`} Comments</h3>
            {comments.map((c) => <CommentThread key={c.id} c={c} />)}
            {commentsCont && <button className="btn ghost" onClick={loadMoreComments}>More comments</button>}
          </div>
        )}
      </div>

      <div className="watch-side mini-hide">
        {related.length > 0 && (
          <>
            <div className="side-head">
              <h2>Up next</h2>
              <button className="rail-toggle" onClick={() => saveSettings({ sidebarCollapsed: true })}
                title="Hide related videos" aria-label="Hide related videos">
                <PanelRightIcon size={18} /> Hide
              </button>
            </div>
            {related.map((v) => <RelatedCard key={v.videoId} video={v} />)}
            <button className="btn ghost side-more" disabled={moreRelated || !relatedToken}
              onClick={loadMoreRelated}>
              {moreRelated ? 'Loading…' : relatedToken ? 'Show more videos' : 'No more videos'}
            </button>
          </>
        )}
      </div>
    </div>
  );
}

// A comment's replies live behind their own continuation token: the line under
// the comment fetches them on first open, then toggles what was fetched.
function CommentThread({ c }: { c: CommentItem }) {
  const [open, setOpen] = useState(false);
  const [replies, setReplies] = useState<CommentItem[]>([]);
  const [cont, setCont] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);

  const load = async (token: string, append: boolean) => {
    setLoading(true);
    setFailed(false);
    const page = await window.indio.comments(token).catch(() => null);
    setLoading(false);
    if (!page) { setFailed(true); return; }
    setReplies((r) => (append ? [...r, ...page.comments] : page.comments));
    setCont(page.continuation && page.continuation !== token ? page.continuation : null);
  };

  const toggle = () => {
    if (!c.repliesToken || loading) return;
    if (!open && !replies.length) load(c.repliesToken, false);
    setOpen((o) => !o);
  };

  return (
    <div className="comment">
      <div className="avatar"><Avatar name={c.author} src={c.avatar} size={36} /></div>
      <div style={{ minWidth: 0 }}>
        <div className="who"><b>{c.author}</b> {c.publishedText || ''}</div>
        <div className="text">{c.text}</div>
        <div className="acts">
          <span className="like-act">
            <ThumbUpIcon size={14} />
            {c.likesText && c.likesText !== '0' ? c.likesText : ''}
          </span>
          {c.replyCountText && (
            c.repliesToken ? (
              <button type="button" className="reply-btn" onClick={toggle} aria-expanded={open}>
                {loading ? 'Loading replies…' : open ? 'Hide replies' : c.replyCountText}
              </button>
            ) : <span>{c.replyCountText}</span>
          )}
        </div>
        {open && (
          <div className="comment-replies">
            {replies.map((r) => (
              <div className="comment comment-reply" key={r.id}>
                <div className="avatar"><Avatar name={r.author} src={r.avatar} size={26} /></div>
                <div style={{ minWidth: 0 }}>
                  <div className="who"><b>{r.author}</b> {r.publishedText || ''}</div>
                  <div className="text">{r.text}</div>
                  <div className="acts">
                    <span className="like-act">
                      <ThumbUpIcon size={13} />
                      {r.likesText && r.likesText !== '0' ? r.likesText : ''}
                    </span>
                  </div>
                </div>
              </div>
            ))}
            {failed && (
              <button type="button" className="reply-btn" onClick={() => load(c.repliesToken!, false)}>
                Couldn't load replies — retry
              </button>
            )}
            {cont && (
              <button type="button" className="reply-btn" onClick={() => load(cont, true)}>
                Show more replies
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
