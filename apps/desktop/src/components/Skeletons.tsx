// Skeleton placeholders. Every skeleton reuses the real layout's dimensions
// (grid gap, thumb aspect, 36px avatars) so content never jumps when it
// arrives — the point of a skeleton is to avoid that shift.
import React from 'react';

export function Sk({ w, h, radius, className, style }: {
  w?: number | string;
  h?: number | string;
  radius?: number;
  className?: string;
  style?: React.CSSProperties;
}) {
  return (
    <div className={`sk${className ? ` ${className}` : ''}`}
      style={{ width: w, height: h, borderRadius: radius, ...style }} />
  );
}

function Lines({ rows = 2 }: { rows?: number }) {
  return (
    <div className="sk-lines">
      <Sk className={`sk-line${rows > 1 ? '' : ' w80'}`} style={{ height: 14 }} />
      {rows > 1 && <Sk className="sk-line w40" />}
    </div>
  );
}

export function VideoCardSkeleton() {
  return (
    <div className="sk-card">
      <Sk className="sk-thumb" />
      <div className="sk-row">
        <Sk className="sk-avatar" />
        <Lines />
      </div>
    </div>
  );
}

export function GridSkeleton({ count = 12, compact = false }: { count?: number; compact?: boolean }) {
  return (
    <div className={`grid${compact ? ' compact' : ''}`}>
      {Array.from({ length: count }, (_, i) => <VideoCardSkeleton key={i} />)}
    </div>
  );
}

export function ListSkeleton({ count = 6 }: { count?: number }) {
  return (
    <div>
      {Array.from({ length: count }, (_, i) => (
        <div className="sk-list-row" key={i}>
          <Sk className="sk-list-thumb" />
          <div className="sk-lines" style={{ gap: 9 }}>
            <Sk className="sk-line" style={{ height: 15, width: '62%' }} />
            <Sk className="sk-line w40" />
            <Sk className="sk-line w60" style={{ height: 10 }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function CommentsSkeleton({ count = 4 }: { count?: number }) {
  return (
    <div className="comments" style={{ marginTop: 26 }}>
      <Sk style={{ width: 160, height: 16, marginBottom: 14 }} />
      {Array.from({ length: count }, (_, i) => (
        <div className="comment" key={i}>
          <Sk className="sk-avatar" style={{ width: 36, height: 36 }} />
          <div className="sk-lines" style={{ gap: 8 }}>
            <Sk className="sk-line" style={{ width: 130, height: 11 }} />
            <Sk className="sk-line w80" />
            <Sk className="sk-line w60" />
          </div>
        </div>
      ))}
    </div>
  );
}

export function WatchSkeleton() {
  return (
    <div className="sk-watch">
      <div className="watch-main">
        <Sk className="sk-player" />
        <Sk style={{ height: 18, width: '68%', margin: '16px 0 12px' }} />
        <div className="sk-row" style={{ alignItems: 'center', marginTop: 0 }}>
          <Sk className="sk-avatar" style={{ width: 40, height: 40 }} />
          <div className="sk-lines" style={{ gap: 8, maxWidth: 260 }}>
            <Sk className="sk-line" style={{ width: 150, height: 13 }} />
            <Sk className="sk-line" style={{ width: 100, height: 10 }} />
          </div>
          <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
            <Sk style={{ width: 96, height: 36, borderRadius: 20 }} />
            <Sk style={{ width: 96, height: 36, borderRadius: 20 }} />
          </div>
        </div>
        <Sk style={{ height: 92, borderRadius: 12, marginTop: 16 }} />
        <CommentsSkeleton count={3} />
      </div>
      <div className="sk-side">
        <Sk style={{ width: 74, height: 15, marginBottom: 4 }} />
        {Array.from({ length: 6 }, (_, i) => (
          <div className="sk-side-row" key={i}>
            <Sk className="sk-side-thumb" />
            <div className="sk-lines" style={{ gap: 9 }}>
              <Sk className="sk-line" style={{ height: 13 }} />
              <Sk className="sk-line w60" style={{ height: 10 }} />
              <Sk className="sk-line w40" style={{ height: 10 }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ChannelSkeleton() {
  return (
    <div>
      <div className="channel-head">
        <Sk style={{ width: 110, height: 110, borderRadius: '50%' }} />
        <div className="sk-lines" style={{ gap: 12, maxWidth: 420 }}>
          <Sk style={{ width: 260, height: 24 }} />
          <Sk className="sk-line w60" style={{ height: 13 }} />
          <Sk style={{ width: 120, height: 36, borderRadius: 20, marginTop: 4 }} />
        </div>
      </div>
      <div className="tabs" style={{ borderBottom: 'none' }}>
        <Sk style={{ width: 90, height: 16 }} />
        <Sk style={{ width: 70, height: 16 }} />
        <Sk style={{ width: 110, height: 16 }} />
      </div>
      <GridSkeleton count={8} />
    </div>
  );
}
