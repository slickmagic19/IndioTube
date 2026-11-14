// Thumbnail variants.
//
// YouTube hands the feed `maxresdefault`/`hq720` posters for every card. A grid
// of twenty of those costs tens of megabytes of decoded bitmaps and a full
// resample per paint, which is noticeable on a tablet GPU. Cards are only a few
// hundred pixels wide, so the 320x180 `mqdefault` variant is indistinguishable
// there — and it is the one poster size that exists for every video.
//
// The watch page keeps the original poster: it covers the whole player.
export interface ThumbChoice {
  src: string | null;
  fallback: string;
}

const YT_IMG = /i\.ytimg\.com\/(vi(?:_webp)?)\/([\w-]+)\/[a-z0-9_]+\.(?:jpg|webp)/i;

/** True for a YouTube poster URL that can be swapped for another variant. */
export function isYtThumb(url: string | null | undefined): boolean {
  return Boolean(url && YT_IMG.test(url));
}

/** The card-sized poster for a URL, or the URL itself when it is not a YouTube one. */
export function thumbVariant(url: string | null | undefined, small: boolean): string | null {
  if (!url) return null;
  if (!small) return url;
  return url.replace(
    YT_IMG,
    (_match, _kind: string, id: string) => `i.ytimg.com/vi/${id}/mqdefault.jpg`,
  );
}

/** The canonical poster for a video id; both variants exist for every video. */
export function thumbUrlFor(videoId: string, small: boolean): string {
  return `https://i.ytimg.com/vi/${videoId}/${small ? 'mqdefault' : 'hqdefault'}.jpg`;
}
