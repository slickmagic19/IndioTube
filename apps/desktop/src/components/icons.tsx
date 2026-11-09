// Inline SVG icon set (no icon dependency; the strokes match YouTube's
// desktop look at 24px on a 24 viewBox).
import React from 'react';

type P = { size?: number; className?: string; style?: React.CSSProperties };
const svg = (d: React.ReactNode, fill = false) =>
  ({ size = 22, className, style }: P) => (
    <svg width={size} height={size} viewBox="0 0 24 24" className={className} style={style}
      fill={fill ? 'currentColor' : 'none'} stroke={fill ? 'none' : 'currentColor'}
      strokeWidth={fill ? 0 : 1.8} strokeLinecap="round" strokeLinejoin="round">
      {d}
    </svg>
  );

export const HomeIcon = svg(<>
  <path d="M4 10.5 12 4l8 6.5" /><path d="M6 9.5V20h12V9.5" />
</>);

export const MenuIcon = svg(<>
  <path d="M4 7h16M4 12h16M4 17h16" />
</>);

export const FireIcon = svg(<>
  <path d="M12 3c1 3-3 4.5-3 8a3.2 3.2 0 0 0 6.2 1c2 1.6 2.8 3 2.8 4.7A6 6 0 1 1 6.4 12C6.4 8 9 6 12 3Z" />
</>);

export const SubsIcon = svg(<>
  <path d="M4 8h16v12H4z" /><path d="M8 4h8" /><path d="M10.5 12.5v4l3.5-2z" fill="currentColor" stroke="none" />
</>);

export const HistoryIcon = svg(<>
  <path d="M12 8v4l3 2" /><path d="M4 12a8 8 0 1 0 2.3-5.6" /><path d="M4 4v4h4" />
</>);

export const ClockIcon = svg(<>
  <circle cx="12" cy="12" r="8.2" /><path d="M12 8v4.3l3 1.8" />
</>);

export const ThumbUpIcon = svg(<>
  <path d="M7 21V10l4-7 1 .5a2.4 2.4 0 0 1 1.3 2.7L12.5 9H18a2 2 0 0 1 2 2.3l-.9 6.2A2.3 2.3 0 0 1 16.8 21H7Z" />
  <path d="M7 10H3v11h4" />
</>);

export const PlaylistIcon = svg(<>
  <path d="M4 7h12M4 12h12M4 17h8" /><path d="M17 13v6l4-3z" fill="currentColor" stroke="none" />
</>);

export const DownloadIcon = svg(<>
  <path d="M12 4v10" /><path d="m7.5 10 4.5 4.5L16.5 10" /><path d="M5 19h14" />
</>);

// Material "settings" cog — solid, with the classic 8-tooth silhouette and
// a cut-out centre. Used in the player controls and the side nav.
const GEAR_PATH = 'M19.14 12.94a7.07 7.07 0 0 0 .05-.94 7.07 7.07 0 0 0-.05-.94l2.03-1.58a.5.5 0 0 0 .12-.61l-1.92-3.32a.5.5 0 0 0-.59-.22l-2.39.96a7.03 7.03 0 0 0-1.62-.94l-.36-2.54a.5.5 0 0 0-.5-.42h-3.84a.5.5 0 0 0-.5.42l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.5.5 0 0 0-.59.22L2.49 8.87a.5.5 0 0 0 .12.61l2.03 1.58a7.07 7.07 0 0 0 0 1.88l-2.03 1.58a.5.5 0 0 0-.12.61l1.92 3.32a.5.5 0 0 0 .59.22l2.39-.96c.49.37 1.03.7 1.62.94l.36 2.54a.5.5 0 0 0 .5.42h3.84a.5.5 0 0 0 .5-.42l.36-2.54a7.03 7.03 0 0 0 1.62-.94l2.39.96a.5.5 0 0 0 .59-.22l1.92-3.32a.5.5 0 0 0-.12-.61zM12 15.6A3.6 3.6 0 1 1 15.6 12 3.6 3.6 0 0 1 12 15.6z';

export const GearIcon = svg(<path d={GEAR_PATH} fill="currentColor" stroke="none" />, true);

export const SettingsIcon = GearIcon;

export const SyncIcon = svg(<>
  <path d="M4.5 9.5a7.5 7.5 0 0 1 13-3.5L20 8" /><path d="M20 3.5V8h-4.5" />
  <path d="M19.5 14.5a7.5 7.5 0 0 1-13 3.5L4 16" /><path d="M4 20.5V16h4.5" />
</>);

export const SearchIcon = svg(<>
  <circle cx="11" cy="11" r="6.5" /><path d="m16 16 4.5 4.5" />
</>);

export const PlayIcon = svg(<path d="M7 4.5 20 12 7 19.5z" fill="currentColor" stroke="none" />, true);
export const PauseIcon = svg(<>
  <path d="M7 4.5h3.4V19.5H7z" fill="currentColor" stroke="none" />
  <path d="M13.6 4.5H17V19.5h-3.4z" fill="currentColor" stroke="none" />
</>, true);
// Material "skip_next" / "skip_previous": solid triangle + bar.
export const NextIcon = svg(<>
  <path d="M6 18l8.5-6L6 6v12z" fill="currentColor" stroke="none" />
  <path d="M16 6h2v12h-2z" fill="currentColor" stroke="none" />
</>, true);
export const PrevIcon = svg(<>
  <path d="M6 6h2v12H6z" fill="currentColor" stroke="none" />
  <path d="M18 18l-8.5-6L18 6v12z" fill="currentColor" stroke="none" />
</>, true);
export const VolumeIcon = svg(<>
  <path d="M4 9.5h3.5L12 5v14L7.5 14.5H4z" fill="currentColor" stroke="none" />
  <path d="M15.5 9.2a4 4 0 0 1 0 5.6M18 6.8a7.5 7.5 0 0 1 0 10.4" />
</>);
export const MuteIcon = svg(<>
  <path d="M4 9.5h3.5L12 5v14L7.5 14.5H4z" fill="currentColor" stroke="none" />
  <path d="m16 9.5 4.5 5M20.5 9.5 16 14.5" />
</>);
export const CaptionsIcon = svg(<>
  <rect x="3" y="6" width="18" height="12" rx="2.5" />
  <path d="M10 10.2a2.4 2.4 0 1 0 0 3.6M16.5 10.2a2.4 2.4 0 1 0 0 3.6" />
</>);
export const HdIcon = svg(<>
  <rect x="3" y="5.5" width="18" height="13" rx="2.5" />
  <path d="M8 9.5v5M8 12h3M11 9.5v5" />
  <path d="M14 9.5v5h1.4a2.5 2.5 0 0 0 0-5H14Z" />
</>);
export const SpeedIcon = svg(<>
  <path d="M5 17a8 8 0 1 1 14 0" />
  <path d="m12 12 3.6-3.6" />
  <circle cx="12" cy="12" r="1.3" fill="currentColor" stroke="none" />
</>);
export const GearSmallIcon = GearIcon;
export const PipIcon = svg(<>
  <rect x="3" y="5.5" width="18" height="13" rx="2" /><rect x="12" y="12" width="7" height="4.5" rx="1" fill="currentColor" stroke="none" />
</>);
export const TheaterIcon = svg(<>
  <rect x="3.5" y="4.5" width="17" height="10" rx="2" /><path d="M3.5 18.5h17" />
</>);
export const MiniIcon = svg(<>
  <rect x="3" y="7" width="12" height="10" rx="2" /><rect x="16.5" y="4" width="5.5" height="5" rx="1.2" fill="currentColor" stroke="none" />
</>);
export const PlusIcon = svg(<path d="M12 5v14M5 12h14" />);
export const CloseIcon = svg(<path d="M6 6l12 12M18 6 6 18" />);
export const BackIcon = svg(<path d="M19 12H5m6-7-7 7 7 7" />);
export const MoreIcon = svg(<>
  <circle cx="12" cy="5" r="1.6" fill="currentColor" stroke="none" />
  <circle cx="12" cy="12" r="1.6" fill="currentColor" stroke="none" />
  <circle cx="12" cy="19" r="1.6" fill="currentColor" stroke="none" />
</>);
export const CheckIcon = svg(<path d="m5 12.5 4.5 4.5L19 7" />);
export const CheckCircleIcon = svg(<>
  <circle cx="12" cy="12" r="8.4" /><path d="m8.4 12.3 2.6 2.6 4.9-5.4" />
</>);
export const PlaylistPlayIcon = svg(<>
  <path d="M4 7h12M4 12h12M4 17h8" />
  <path d="M17 13.5v5.5l4-2.75z" fill="currentColor" stroke="none" />
</>);
export const QueueIcon = svg(<>
  <path d="M4 7h12M4 12h12M4 17h8" />
  <path d="M18 11v6M15 14h6" />
</>);
export const BlockIcon = svg(<>
  <circle cx="12" cy="12" r="8.4" /><path d="m6.2 17.8 11.6-11.6" />
</>);
export const EyeOffIcon = svg(<>
  <path d="M4 12s2.8-5 8-5 8 5 8 5-2.8 5-8 5-8-5-8-5z" />
  <path d="m5 19 14-14" />
</>);
export const ThumbDownIcon = svg(<>
  <path d="M17 3v11l-4 7-1-.5a2.4 2.4 0 0 1-1.3-2.7L11.5 15H6a2 2 0 0 1-2-2.3l.9-6.2A2.3 2.3 0 0 1 7.2 3H17Z" />
  <path d="M17 14h4V3h-4" />
</>);
export const ShareIcon = svg(<>
  <path d="M13 5 21 12l-8 7v-4C8 14.5 5 12.5 3 8c2.5 2 4.5 2.5 10 2.5V5Z" />
</>);
export const QrIcon = svg(<>
  <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
  <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" /><path d="M13.5 13.5h3v3M17 20.5h3.5V17" />
</>);
export const FolderIcon = svg(<>
  <path d="M3.5 7.5a2 2 0 0 1 2-2H10l2 2h6a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2Z" />
</>);
export const TrashIcon = svg(<>
  <path d="M4.5 7.5h15M9 7.5V5h6v2.5M6.5 7.5 7.5 20h9l1-12.5" />
</>);

export const FullscreenIcon = svg(<>
  <path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />
</>);

export const ExitFullscreenIcon = svg(<>
  <path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" />
</>);
