// Sync modal: starts the LAN pairing server, paints its QR, and shows live
// status while the phone pulls the desktop library and pushes its own back.
// Exactly the Awitize flow: the QR holds { indio, v, host, port, token,
// name }; the merge is add-only on both sides and never overwrites anything.
import React, { useEffect, useRef, useState } from 'react';
import QRCode from 'qrcode';
import type { SyncStats } from '../types';
import { useApp } from '../store';

function summarize(s: SyncStats): string {
  const parts: string[] = [];
  if (s.historyAdded) parts.push(`+${s.historyAdded} history`);
  if (s.watchLaterAdded) parts.push(`+${s.watchLaterAdded} watch later`);
  if (s.likedAdded) parts.push(`+${s.likedAdded} liked`);
  if (s.playlistsAdded) parts.push(`+${s.playlistsAdded} playlists`);
  if (s.playlistsMerged) parts.push(`${s.playlistsMerged} playlists updated`);
  if (s.subsAdded) parts.push(`+${s.subsAdded} subscriptions`);
  return parts.length ? parts.join(' · ') : 'already in sync';
}

export function SyncModal({ onClose }: { onClose(): void }) {
  const { toast } = useApp();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [status, setStatus] = useState<'starting' | 'waiting' | 'linked' | 'error'>('starting');
  const [error, setError] = useState('');
  const [lastMerge, setLastMerge] = useState<string | null>(null);
  const [payload, setPayload] = useState<Record<string, unknown> | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (!payload || !canvasRef.current) return;
    // 320px with a real quiet zone keeps the code readable from a phone even
    // when the window is scaled; 240px was cramped enough to fail on cameras
    // that pick the smallest preview size.
    QRCode.toCanvas(canvasRef.current, JSON.stringify(payload), {
      width: 320, margin: 2, errorCorrectionLevel: 'M',
    }).catch(() => setError('Could not render the QR code'));
  }, [payload]);

  const manualText = payload ? `${payload.host} ${payload.port} ${payload.token}` : '';
  const copyManual = async () => {
    if (!manualText) return;
    try {
      await navigator.clipboard.writeText(manualText);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      setError('Could not copy — select the text below and copy it manually.');
    }
  };

  useEffect(() => {
    let stopped = false;
    let server: { stop(): Promise<unknown> } | null = null;

    const off = window.indio.sync.onEvent((evt) => {
      if (stopped) return;
      if (evt.type === 'linked') setStatus('linked');
      else if (evt.type === 'pulled') setStatus('linked');
      else if (evt.type === 'merged') {
        setLastMerge(evt.stats ? summarize(evt.stats) : 'merged');
        toast(evt.sender ? `Merged with ${evt.sender}` : 'Merged with your phone');
      } else if (evt.type === 'expired') { onClose(); }
      else if (evt.type === 'error') { setError(evt.message || 'sync error'); setStatus('error'); }
    });

    window.indio.sync.start()
      .then((res) => {
        if (stopped) { window.indio.sync.stop(); return; }
        server = res as any;
        setPayload(res.payload);
        setStatus('waiting');
      })
      .catch((e) => { if (!stopped) { setError(String(e?.message || e)); setStatus('error'); } });

    return () => {
      stopped = true;
      off();
      void window.indio.sync.stop();
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <h3>Sync with your phone</h3>
        <div className="qr-box">
          {status === 'starting' && <div className="state"><span>Starting the pairing server…</span></div>}
          {status === 'error' && <div className="qr-status" style={{ color: 'var(--accent)' }}>{error}</div>}
          {(status === 'waiting' || status === 'linked') && (
            <>
              <canvas ref={canvasRef} />
              <div className="qr-status">
                {status === 'waiting'
                  ? 'Open IndioTube on your phone → You → Sync with PC and scan this code. Both phones and PC must use the same Wi-Fi.'
                  : 'Phone linked — it pulled your desktop library and pushed its own back.'}
              </div>
              {lastMerge && <div className="qr-status"><b>Last merge:</b> {lastMerge}</div>}
              {payload && (
                <div className="qr-pair">
                  <div className="qr-pair-title">No camera? Enter these on the phone under “Enter PC address manually”</div>
                  <div className="qr-pair-row"><span>Host</span><code>{String(payload.host)}</code></div>
                  <div className="qr-pair-row"><span>Port</span><code>{String(payload.port)}</code></div>
                  <div className="qr-pair-row"><span>Token</span><code>{String(payload.token)}</code></div>
                  <button className="btn ghost" onClick={copyManual}>
                    {copied ? 'Copied ✓' : 'Copy address, port and token'}
                  </button>
                </div>
              )}
            </>
          )}
          <button className="btn ghost" onClick={onClose} style={{ marginTop: 8 }}>Close</button>
        </div>
      </div>
    </div>
  );
}
