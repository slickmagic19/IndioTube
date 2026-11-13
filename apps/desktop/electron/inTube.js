// YouTube metadata via the InnerTube WEB client — the JavaScript port of the
// Android app's InnerTube.kt. Same endpoints, same config bootstrap, same
// layout-tolerant parsing conventions: known paths first, then a bounded tree
// scan. Ads and Shorts never reach the UI because their renderers are
// skipped during parsing, which is what makes the app ad-free without
// touching playback.
//
// The parsers are exported pure functions (no network) so vitest can run the
// same fixture vectors the Android parser tests use.
const LANG = (navigatorLocale().split('-')[0] || 'en').toLowerCase();
const COUNTRY = ((Intl.DateTimeFormat().resolvedOptions().timeZone || '').split('/')[0] === 'UTC'
  ? 'US'
  : localeCountry());

function navigatorLocale() { return (Intl.DateTimeFormat().resolvedOptions().locale) || 'en-US'; }
function localeCountry() {
  try {
    const region = new Intl.DateTimeFormat().resolvedOptions().region;
    return region && region.length === 2 ? region.toUpperCase() : 'US';
  } catch { return 'US'; }
}

const UA_DESKTOP =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0';

const DURATION_RE = /^\d{1,2}:\d{2}(:\d{2})?$/;
const VIEWS_RE = /(views?|watching|likes?)/i;
const AGO_RE = /(ago|Premiered|Streamed|\b\d{4}\b)/i;
const LIKES_ALONG_RE = /along with ([\d.,]+[KMB]?) other/i;
const COUNT_RE = /^[\d.,]+[KMB]?$/;
const MEMBERS_RE = /\bmembers?[- ]only\b|\bchannel members?\b/i;
const PLAYLIST_COUNT_RE = /\bvideos?$/i;

// Search params for "this week, videos only, sorted by view count" - the
// closest signed-out equivalent of the retired FEtrending feed.
const TRENDING_PARAMS = 'EgYIAxABGAM%3D';
const CHANNEL_TAB_VIDEOS = 'EgZ2aWRlb3PyBgQKAjoA';
const CHANNEL_TAB_LIVE = 'EgdzdHJlYW1z8gYECgJ6AA%3D%3D';
// Fallback only: the Playlists tab params change per channel, so the live
// value is read from the channel's own tab renderers first.
const CHANNEL_TAB_PLAYLISTS = 'EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA';

// ---- JSON navigation helpers (port of Json.kt) -----------------------------

function field(el, key) {
  if (el && typeof el === 'object' && !Array.isArray(el)) return el[key] ?? null;
  return null;
}
function obj(el, key) { const v = field(el, key); return v && typeof v === 'object' && !Array.isArray(v) ? v : null; }
function arr(el, key) { const v = field(el, key); return Array.isArray(v) ? v : null; }
function str(el, key) {
  const v = el && typeof el === 'object' && !Array.isArray(el) ? el[key] : undefined;
  return typeof v === 'string' ? v : null;
}

function path(el, ...keys) {
  let cur = el;
  for (const k of keys) {
    if (cur == null) return null;
    cur = typeof k === 'number'
      ? (Array.isArray(cur) ? (cur[k] ?? null) : null)
      : (typeof cur === 'object' && !Array.isArray(cur) ? (cur[k] ?? null) : null);
    if (cur == null) return null;
  }
  return cur;
}

// Terminal must be a primitive (mirrors Kotlin pathStr: objects yield null).
function pathStr(el, ...keys) {
  const v = path(el, ...keys);
  if (v == null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
}

// Bounded breadth-first scan for every value stored under `key`.
function findAll(el, key, maxDepth = 14, limit = 60) {
  const out = [];
  if (el == null) return out;
  let current = [el];
  let depth = 0;
  while (current.length && depth <= maxDepth && out.length < limit) {
    const next = [];
    for (const cur of current) {
      if (cur && typeof cur === 'object') {
        if (Array.isArray(cur)) {
          for (const v of cur) next.push(v);
        } else {
          if (cur[key] !== undefined && out.length < limit) out.push(cur[key]);
          for (const v of Object.values(cur)) next.push(v);
        }
      }
    }
    current = next;
    depth += 1;
  }
  return out;
}

// Join the text of a runs[] array ("title": {"runs": [{"text": "a"}, ...]}).
function runsText(el) {
  const runs = el && typeof el === 'object' && !Array.isArray(el) ? el.runs : null;
  if (!Array.isArray(runs)) return null;
  const joined = runs.map((r) => pathStr(r, 'text')).filter(Boolean).join('').trim();
  return joined || null;
}

function textEither(el) { return str(el, 'simpleText') ?? runsText(el); }

// ---- URL / text helpers (port of Models.kt) ---------------------------------

function normalizeImageUrl(value) {
  const url = typeof value === 'string' ? value.trim() : '';
  if (!url) return null;
  if (url.startsWith('//')) return `https:${url}`;
  if (url.startsWith('http://')) return `https://${url.slice(7)}`;
  return url;
}

function parseDuration(text) {
  if (!text) return 0;
  const parts = String(text).trim().split(':').map((p) => Number(p.trim()));
  const nums = parts.filter((n) => Number.isFinite(n));
  if (nums.length !== parts.length || !nums.length) return 0;
  if (nums.length === 3) return nums[0] * 3600 + nums[1] * 60 + nums[2];
  if (nums.length === 2) return nums[0] * 60 + nums[1];
  return nums[0];
}

// "21 million views" -> "21M views" so meta lines stay YouTube-tight.
function compactCount(label) {
  return String(label)
    .replace(/\s*thousand\b/gi, 'K')
    .replace(/\s*billion\b/gi, 'B')
    .replace(/\s*million\b/gi, 'M')
    .replace(/\s+/g, ' ')
    .trim();
}

// ---- search params (port of buildSearchParams, protobuf varints) ------------

function writeVarint(value, out) {
  let v = value;
  while (v >= 0x80) {
    out.push(((v & 0x7f) | 0x80) & 0xff);
    v >>>= 7;
  }
  out.push(v & 0xff);
}

function writeField(fieldNo, value, out) {
  out.push((fieldNo << 3) & 0xff);
  writeVarint(value, out);
}

// Null means "no filter" for every argument. YouTube orders the duration
// values short=1, long=2, medium=3 - not by length.
function buildSearchParams({ type = null, sort = null, uploadDate = null, duration = null } = {}) {
  const filters = [];
  if (uploadDate != null) writeField(1, uploadDate, filters);
  if (type != null) writeField(2, type, filters);
  if (duration != null) writeField(3, duration, filters);

  const message = [];
  if (sort != null) writeField(1, sort, message);
  if (filters.length) {
    message.push(0x12); // nested filters: field 2, length-delimited
    writeVarint(filters.length, message);
    message.push(...filters);
  }
  if (!message.length) return null;
  return Buffer.from(message).toString('base64');
}

// ---- badge helpers -----------------------------------------------------------

function hasMembersBadge(el, maxDepth = 12) {
  if (el == null) return false;
  const labels = (key, ...keys) =>
    findAll(el, key, maxDepth, 24).map((b) => pathStr(b, ...keys));
  const texts = [
    ...labels('metadataBadgeRenderer', 'label'),
    ...labels('thumbnailBadgeViewModel', 'text'),
    ...labels('badgeViewModel', 'title', 'content'),
    ...labels('badgeViewModel', 'badgeText', 'content'),
    ...labels('badgeViewModel', 'text'),
    ...labels('badgeViewModel', 'label'),
  ].filter(Boolean);
  const styled = findAll(el, 'metadataBadgeRenderer', maxDepth, 24)
    .some((b) => pathStr(b, 'style') === 'BADGE_STYLE_TYPE_MEMBERS_ONLY');
  return styled || texts.some((t) => MEMBERS_RE.test(t));
}

// Every item that goes through a parser gets checked here: vertical
// thumbnails (Shorts are portrait) and reel navigation endpoints never
// reach the UI.
function isShortish(el) {
  if (path(el, 'navigationEndpoint', 'reelWatchEndpoint') != null) return true;
  const thumbs = findAll(el, 'thumbnails', 6, 6).filter(Array.isArray).flat();
  const last = thumbs.length ? thumbs[thumbs.length - 1] : null;
  if (!last) return false;
  const w = Number(pathStr(last, 'width') || 0);
  const h = Number(pathStr(last, 'height') || 0);
  return h > w && w > 0;
}

// ---- renderer parsing (port of InnerTube.kt parse*) --------------------------
// A verified channel carries a check-circle badge in its owner badges. The
// renderer shapes differ across layouts, so the badge icon type is the one
// stable marker ("Verified" only exists as its accessibility label).
function isVerified(...nodes) {
  for (const node of nodes) {
    if (!node) continue;
    try {
      if (/CHECK_CIRCLE_THICK|BADGE_STYLE_TYPE_VERIFIED|"label":"Verified"/.test(JSON.stringify(node))) return true;
    } catch { /* cyclic or exotic shape */ }
  }
  return false;
}

function lastThumbUrl(container) {
  const thumbs = container && typeof container === 'object' ? container.thumbnails : null;
  if (!Array.isArray(thumbs)) return null;
  for (let i = thumbs.length - 1; i >= 0; i -= 1) {
    const url = normalizeImageUrl(pathStr(thumbs[i], 'url'));
    if (url) return url;
  }
  return null;
}

function xthFirstUrl(container) {
  const sources = findAll(container, 'sources', 6, 4).filter(Array.isArray).flat();
  for (let i = sources.length - 1; i >= 0; i -= 1) {
    const url = normalizeImageUrl(pathStr(sources[i], 'url'));
    if (url) return url;
  }
  return null;
}

function parseVideoRenderer(v) {
  const videoId = pathStr(v, 'videoId');
  if (!videoId) return null;
  const titleEl = path(v, 'title');
  const title = ((titleEl && runsText(titleEl)) || str(titleEl, 'simpleText') || '').trim();
  if (!title) return null;
  if (field(v, 'upcomingEventData')) return null;
  const badges = arr(v, 'badges') || [];
  let membersOnly = badges.some((b) =>
    (pathStr(b, 'metadataBadgeRenderer', 'label') || '').includes('Members') ||
    (pathStr(b, 'metadataBadgeRenderer', 'label') || '').toLowerCase().includes('members'));
  membersOnly = membersOnly || hasMembersBadge(v, 6);
  if (isShortish(v)) return null;

  const live = badges.some((b) => pathStr(b, 'metadataBadgeRenderer', 'style') === 'BADGE_STYLE_TYPE_LIVE_NOW')
    || pathStr(v, 'lengthText', 'simpleText') === 'LIVE';

  const lengthEl = path(v, 'lengthText');
  const durationText = (lengthEl && str(lengthEl, 'simpleText')) || (lengthEl && runsText(lengthEl)) || null;
  const duration = parseDuration(durationText);

  const vc = path(v, 'viewCountText');
  const svc = path(v, 'shortViewCountText');
  const views = (vc && str(vc, 'simpleText')) || (vc && runsText(vc))
    || (svc && str(svc, 'simpleText')) || (svc && runsText(svc)) || null;
  const pt = path(v, 'publishedTimeText');
  const published = (pt && str(pt, 'simpleText')) || (pt && runsText(pt)) || null;

  const ownerList = [
    path(v, 'ownerText', 'runs'),
    path(v, 'longBylineText', 'runs'),
    path(v, 'shortBylineText', 'runs'),
    path(v, 'videoOwnerRenderer', 'title', 'runs'),
  ].find(Array.isArray) || [];
  const owner0 = ownerList[0] || {};
  const channelName = pathStr(owner0, 'text') || undefined;
  const channelId = pathStr(owner0, 'navigationEndpoint', 'browseEndpoint', 'browseId')
    || pathStr(v, 'navigationEndpoint', 'browseEndpoint', 'browseId') || undefined;
  const channelAvatar = lastThumbUrl(path(v, 'channelThumbnailSupportedRenderers', 'channelThumbnailWithLinkRenderer', 'thumbnail'))
    || lastThumbUrl(path(v, 'channelThumbnail')) || undefined;

  const thumbnail = lastThumbUrl(path(v, 'thumbnail')) || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`;

  return {
    videoId, title,
    channelName: channelName || undefined,
    channelId: channelId || undefined,
    channelAvatar: channelAvatar || undefined,
    channelVerified: isVerified(v.ownerBadges, v.badges, v.videoOwnerRenderer && v.videoOwnerRenderer.badges) || undefined,
    thumbnail,
    durationSec: duration,
    viewsText: views || undefined,
    publishedText: published || undefined,
    isLive: live,
    membersOnly,
  };
}

// New universal item format (lockupViewModel) used by newer layouts.
function parseLockup(lockup) {
  const contentType = pathStr(lockup, 'contentType');
  if (!contentType || !contentType.includes('VIDEO')) return null;
  const videoId = pathStr(lockup, 'contentId');
  if (!videoId) return null;
  const meta = path(lockup, 'metadata', 'lockupMetadataViewModel');
  if (!meta) return null;
  const title = (pathStr(meta, 'title', 'content') || '').trim();
  if (!title) return null;

  const thumbContainer =
    path(meta, 'image', 'collectionThumbnailViewModel', 'primaryThumbnail', 'thumbnailViewModel')
    || path(lockup, 'contentImage', 'thumbnailViewModel')
    || path(lockup, 'contentImage', 'collectionThumbnailViewModel', 'primaryThumbnail', 'thumbnailViewModel');
  const thumbUrl = xthFirstUrl(thumbContainer && path(thumbContainer, 'image'));
  if (thumbContainer && isShortish(thumbContainer)) return null;

  let durationBadge = null;
  let isLive = false;
  for (const ov of (thumbContainer && arr(thumbContainer, 'overlays')) || []) {
    for (const badge of findAll(ov, 'thumbnailBadgeViewModel', 6, 4)) {
      const text = pathStr(badge, 'text');
      if (!text) continue;
      if (text.toUpperCase() === 'LIVE') isLive = true;
      else if (DURATION_RE.test(text)) durationBadge = text;
    }
  }

  let channelName;
  let channelId;
  let channelAvatar;
  let views;
  let published;
  let membersOnly = hasMembersBadge(lockup, 10) || hasMembersBadge(thumbContainer, 6);

  const avatarVm = path(meta, 'image', 'decoratedAvatarViewModel');
  channelAvatar = xthFirstUrl(avatarVm && path(avatarVm, 'avatar')) || undefined;
  channelId = pathStr(avatarVm,
    'rendererContext', 'commandContext', 'onTap', 'innertubeCommand', 'browseEndpoint', 'browseId') || undefined;
  const a11yName = pathStr(avatarVm, 'a11yLabel');
  if (a11yName) {
    const cleaned = a11yName.replace(/^Go to channel /, '').trim();
    if (cleaned) channelName = cleaned;
  }

  const rows = (path(meta, 'metadata', 'contentMetadataViewModel', 'metadataRows') || []);
  for (const row of Array.isArray(rows) ? rows : []) {
    for (const part of (arr(row, 'metadataParts') || [])) {
      const t = pathStr(part, 'text', 'content');
      const a11y = pathStr(part, 'accessibilityLabel');
      const membersText = (t && MEMBERS_RE.test(t)) || (a11y && MEMBERS_RE.test(a11y));
      if (membersText) membersOnly = true;
      if (membersText) { /* tagged above */ }
      else if (t && VIEWS_RE.test(t)) { if (!views) views = t; }
      else if (t && AGO_RE.test(t) && !DURATION_RE.test(t)) { if (!published) published = t; }
      else if (a11y && VIEWS_RE.test(a11y)) { if (!views) views = compactCount(a11y); }
      else if (t && COUNT_RE.test(t)) { if (!views) views = `${t} views`; }
      else if (t) { if (!channelName) channelName = t; }
      if (!channelAvatar) channelAvatar = xthFirstUrl(path(part, 'avatar')) || undefined;
      if (!channelId) {
        channelId = pathStr(part,
          'text', 'commandRuns', 0, 'onTap', 'innertubeCommand', 'browseEndpoint', 'browseId') || undefined;
      }
    }
  }

  return {
    videoId, title,
    channelName: channelName || undefined,
    channelId: channelId || undefined,
    channelAvatar: channelAvatar || undefined,
    channelVerified: isVerified(meta) || undefined,
    thumbnail: thumbUrl || `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
    durationSec: parseDuration(durationBadge),
    viewsText: views || undefined,
    publishedText: published || undefined,
    isLive,
    membersOnly,
  };
}

function parseChannelRenderer(c) {
  const channelId = pathStr(c, 'channelId');
  if (!channelId) return null;
  const titleEl = path(c, 'title');
  const name = (titleEl && str(titleEl, 'simpleText')) || (titleEl && runsText(titleEl));
  if (!name) return null;
  const avatar = lastThumbUrl(path(c, 'thumbnail'));
  const subsEl = path(c, 'subscriberCountText');
  const vidEl = path(c, 'videoCountText');
  const subs = (subsEl && str(subsEl, 'simpleText')) || (subsEl && runsText(subsEl))
    || (vidEl && str(vidEl, 'simpleText')) || (vidEl && runsText(vidEl)) || null;
  const description = runsText(path(c, 'descriptionSnippet'));
  return { channelId, name, avatar: avatar || undefined, subscribersText: subs || undefined, videoCountText: null, description: description || undefined };
}

function bylineFirst(p, key) {
  const runs = path(p, key, 'runs');
  return Array.isArray(runs) && runs[0] ? runs[0] : {};
}

function parsePlaylistRenderer(p) {
  const playlistId = pathStr(p, 'playlistId');
  if (!playlistId) return null;
  const titleEl = path(p, 'title');
  const title = (titleEl && str(titleEl, 'simpleText')) || (titleEl && runsText(titleEl));
  if (!title) return null;
  const vcEl = path(p, 'videoCountText');
  const count = (vcEl && str(vcEl, 'simpleText')) || (vcEl && runsText(vcEl))
    || (pathStr(p, 'videoCount') ? `${pathStr(p, 'videoCount')} videos` : null);
  const short0 = bylineFirst(p, 'shortBylineText');
  const long0 = bylineFirst(p, 'longBylineText');
  const channel = pathStr(short0, 'text') || pathStr(long0, 'text') || null;
  const channelId = pathStr(short0, 'navigationEndpoint', 'browseEndpoint', 'browseId') || null;
  const thumb = lastThumbUrl(path(p, 'thumbnail'));
  return { playlistId, title, channelName: channel || undefined, channelId: channelId || undefined, thumbnail: thumb || undefined, videoCountText: count || undefined };
}

function parsePlaylistLockup(lockup) {
  const playlistId = pathStr(lockup, 'contentId');
  if (!playlistId) return null;
  const meta = path(lockup, 'metadata', 'lockupMetadataViewModel');
  if (!meta) return null;
  const title = (pathStr(meta, 'title', 'content') || '').trim();
  if (!title) return null;
  const thumbVm = path(lockup, 'contentImage', 'collectionThumbnailViewModel', 'primaryThumbnail', 'thumbnailViewModel')
    || path(lockup, 'contentImage', 'thumbnailViewModel')
    || path(meta, 'image', 'collectionThumbnailViewModel', 'primaryThumbnail', 'thumbnailViewModel');
  const thumb = xthFirstUrl(thumbVm && path(thumbVm, 'image')) || xthFirstUrl(path(meta, 'image'));
  let count = null;
  const countRe = /\b(videos?|episodes?)\b/i;
  for (const badge of findAll(thumbVm, 'thumbnailBadgeViewModel', 8, 6)) {
    const text = pathStr(badge, 'text');
    if (text && countRe.test(text) && !count) count = text;
  }
  const rows = path(meta, 'metadata', 'contentMetadataViewModel', 'metadataRows') || [];
  for (const row of Array.isArray(rows) ? rows : []) {
    for (const part of (arr(row, 'metadataParts') || [])) {
      const t = pathStr(part, 'text', 'content');
      if (t && countRe.test(t) && !count) count = t;
    }
  }
  return { playlistId, title, channelName: null, channelId: null, thumbnail: thumb || undefined, videoCountText: count || undefined };
}

// ---- feed / home --------------------------------------------------------------

function extractContinuation(cont) {
  return pathStr(cont, 'continuationEndpoint', 'continuationCommand', 'token')
    || pathStr(cont, 'button', 'buttonRenderer', 'command', 'continuationCommand', 'token');
}

function feedContents(root) {
  const direct = path(root,
    'contents', 'twoColumnBrowseResultsRenderer', 'tabs', 0,
    'tabRenderer', 'content', 'richGridRenderer', 'contents');
  if (Array.isArray(direct)) return direct;
  const single = path(root,
    'contents', 'singleColumnBrowseResultsRenderer', 'tabs', 0,
    'tabRenderer', 'content', 'richGridRenderer', 'contents');
  if (Array.isArray(single)) return single;
  // section-list layouts (experiments): flatten grid renderers
  const out = [];
  for (const grid of findAll(root, 'richGridRenderer', 8, 3)) {
    const contents = arr(grid, 'contents');
    if (contents) out.push(...contents);
  }
  return out;
}

function feedContinuation(root) {
  for (const cont of findAll(root, 'continuationItemRenderer', 14, 12)) {
    const t = extractContinuation(cont);
    if (t) return t;
  }
  for (const cc of findAll(root, 'continuationCommand', 14, 12)) {
    const t = pathStr(cc, 'token');
    if (t) return t;
  }
  return null;
}

// Browse continuations arrive outside `contents`: the next page of items is
// in onResponseReceivedActions / onResponseReceivedEndpoints /
// onResponseReceivedCommands (appendContinuationItemsAction or
// reloadContinuationItemsCommand). Looking only under `contents` made every
// "load more" page parse as empty even though the token was valid.
function continuationActionItems(root) {
  const out = [];
  const add = (el) => {
    if (!el || typeof el !== 'object') return;
    const items = path(el, 'appendContinuationItemsAction', 'continuationItems')
      || path(el, 'reloadContinuationItemsCommand', 'continuationItems');
    if (Array.isArray(items)) out.push(...items);
    const commands = path(el, 'commandExecutorCommand', 'commands');
    if (Array.isArray(commands)) for (const cmd of commands) add(cmd);
  };
  for (const key of ['onResponseReceivedActions', 'onResponseReceivedEndpoints', 'onResponseReceivedCommands']) {
    const list = root && root[key];
    if (Array.isArray(list)) for (const el of list) add(el);
  }
  return out;
}

// Initial renders keep the grid under `contents`; subsequent pages keep it
// in a continuation action. Both feed the same item loop.
function feedItems(root) {
  const direct = feedContents(root);
  return direct.length ? direct : continuationActionItems(root);
}

function recommendableVideos(videos) {
  return videos.filter((v) => !v.membersOnly);
}

function parseFeed(root) {
  const chips = [];
  const chipArr = path(root,
    'contents', 'twoColumnBrowseResultsRenderer', 'tabs', 0,
    'tabRenderer', 'content', 'richGridRenderer', 'header',
    'feedFilterChipBarRenderer', 'contents');
  for (const chipEl of Array.isArray(chipArr) ? chipArr : []) {
    const chip = obj(chipEl, 'chipCloudChipRenderer');
    if (!chip) continue;
    const textEl = path(chip, 'text');
    const title = (textEl && str(textEl, 'simpleText')) || (textEl && runsText(textEl));
    if (!title) continue;
    chips.push({
      title,
      params: pathStr(chip, 'navigationEndpoint', 'browseEndpoint', 'params') || null,
      token: pathStr(chip, 'navigationEndpoint', 'continuationCommand', 'token') || null,
    });
  }

  const contents = feedItems(root);
  const videos = [];
  let continuation = null;
  for (const entry of contents) {
    const item = obj(entry, 'richItemRenderer');
    if (item) {
      const content = field(item, 'content');
      const v = content && parseAnyVideo(content);
      if (v) videos.push(v);
      continue;
    }
    const cont = obj(entry, 'continuationItemRenderer');
    if (cont) continuation = extractContinuation(cont) || continuation;
  }
  if (!continuation) continuation = feedContinuation(root);
  return { chips, videos, continuation };
}

function parseAnyVideo(content) {
  for (const key of ['videoRenderer', 'gridVideoRenderer', 'compactVideoRenderer', 'playlistVideoRenderer']) {
    const r = obj(content, key);
    if (r) return parseVideoRenderer(r);
  }
  const lockup = obj(content, 'lockupViewModel');
  if (lockup) return parseLockup(lockup);
  // richSectionRenderer (shelves incl. Shorts rails), reelShelfRenderer,
  // promotedSparklesWebRenderer, adSlotRenderer → skipped on purpose
  return null;
}

// ---- search ---------------------------------------------------------------------

function parseSearch(root) {
  const entries = [];
  let continuation = null;
  let sections = path(root,
    'contents', 'tabbedSearchResultsRenderer', 'tabs', 0,
    'tabRenderer', 'content', 'sectionListRenderer', 'contents');
  if (!Array.isArray(sections)) {
    sections = path(root,
      'contents', 'twoColumnSearchResultsRenderer', 'primaryContents',
      'sectionListRenderer', 'contents');
  }
  const sectionList = Array.isArray(sections)
    ? sections
    : findAll(root, 'itemSectionRenderer', 12, 20);
  for (const section of sectionList) {
    const itemSection = obj(section, 'itemSectionRenderer')
      || (section && typeof section === 'object' && !Array.isArray(section) ? section : null);
    const items = itemSection && arr(itemSection, 'contents');
    if (!items) continue;
    for (const el of items) {
      const vr = obj(el, 'videoRenderer');
      if (vr) { const v = parseVideoRenderer(vr); if (v) entries.push({ kind: 'video', video: v }); continue; }
      const cr = obj(el, 'channelRenderer');
      if (cr) { const c = parseChannelRenderer(cr); if (c) entries.push({ kind: 'channel', channel: c }); continue; }
      const pr = obj(el, 'playlistRenderer');
      if (pr) { const p = parsePlaylistRenderer(pr); if (p) entries.push({ kind: 'playlist', playlist: p }); continue; }
      const lv = obj(el, 'lockupViewModel');
      if (lv) { const v = parseLockup(lv); if (v) entries.push({ kind: 'video', video: v }); continue; }
      const cont = obj(el, 'continuationItemRenderer');
      if (cont) { const t = extractContinuation(cont); if (t) continuation = t; }
      // shelfRenderer/reelShelfRenderer → skipped (Shorts & shelves)
    }
  }
  if (!continuation) {
    for (const cont of findAll(root, 'continuationItemRenderer', 12, 10)) {
      const t = extractContinuation(cont);
      if (t) { continuation = t; break; }
    }
  }
  // Age-gated searches come back as a background promo ("Confirm your
  // age") with no renderers at all; treat that as restricted, not empty.
  const restricted = findAll(root, 'backgroundPromoRenderer', 12, 4).length > 0;
  return { entries, continuation, restricted };
}

// Type-ahead completions: the endpoint answers JSONP (goog.ac.h([...])).
function parseSuggestionData(text) {
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start < 0 || end <= start) return { suggestions: [], channels: {} };
  let parsed;
  try { parsed = JSON.parse(text.slice(start, end + 1)); } catch { return { suggestions: [], channels: {} }; }
  const rows = Array.isArray(parsed) && Array.isArray(parsed[1]) ? parsed[1] : null;
  if (!rows) return { suggestions: [], channels: {} };
  const suggestions = [];
  const channels = {};
  for (const row of rows) {
    if (!Array.isArray(row)) continue;
    const label = typeof row[0] === 'string' ? row[0].trim() : '';
    if (!label || suggestions.includes(label)) continue;
    suggestions.push(label);
    const meta = row.find((x) => x && typeof x === 'object' && !Array.isArray(x));
    if (!meta) continue;
    const channelId = typeof meta.zav === 'string' ? meta.zav.trim() : '';
    if (!channelId) continue;
    channels[label.toLowerCase()] = {
      query: label,
      channelId,
      name: (typeof meta.zao === 'string' && meta.zao.trim()) ? meta.zao.trim() : label,
      handle: (typeof meta.zaf === 'string' && meta.zaf.trim()) ? meta.zaf.trim() : null,
      avatar: normalizeImageUrl(typeof meta.zai === 'string' ? meta.zai : null),
    };
  }
  return { suggestions: suggestions.slice(0, 12), channels };
}

// Entries shown when a search was recovered through a suggested channel.
function fallbackSearchEntries(channel, videos, playlists, type, duration) {
  const D = { SHORT: 1, LONG: 2, MEDIUM: 3 };
  const F = { VIDEOS: 1, CHANNELS: 2, PLAYLISTS: 3 };
  let matching = videos;
  if (duration === D.SHORT) matching = videos.filter((v) => v.durationSec >= 1 && v.durationSec <= 239);
  else if (duration === D.MEDIUM) matching = videos.filter((v) => v.durationSec >= 240 && v.durationSec <= 1200);
  else if (duration === D.LONG) matching = videos.filter((v) => v.durationSec > 1200);
  const out = [];
  if (type === F.CHANNELS) out.push({ kind: 'channel', channel });
  else if (type === F.VIDEOS) {
    matching.slice(0, 20).forEach((v) => out.push({ kind: 'video', video: v }));
    if (!out.length) out.push({ kind: 'channel', channel });
  } else if (type === F.PLAYLISTS) {
    playlists.slice(0, 20).forEach((p) => out.push({ kind: 'playlist', playlist: p }));
    if (!out.length) out.push({ kind: 'channel', channel });
  } else {
    out.push({ kind: 'channel', channel });
    matching.slice(0, 20).forEach((v) => out.push({ kind: 'video', video: v }));
  }
  return out;
}

// ---- channel -----------------------------------------------------------------------

function parseChannelHeader(root, requestedId) {
  let name = null; let avatar = null; let subscribers = null; let videoCount = null; let description = null;

  const pageHeader = path(root, 'header', 'pageHeaderRenderer');
  if (pageHeader) {
    name = pathStr(pageHeader, 'pageHeaderViewModel', 'title', 'dynamicTextViewModel', 'text', 'content');
    avatar = xthFirstUrl(path(pageHeader, 'pageHeaderViewModel', 'image', 'decoratedAvatarViewModel', 'avatar'));
    const rows = path(pageHeader, 'pageHeaderViewModel', 'metadata', 'contentMetadataViewModel', 'metadataRows');
    for (const row of Array.isArray(rows) ? rows : []) {
      for (const part of (arr(row, 'metadataParts') || [])) {
        const t = pathStr(part, 'text', 'content');
        if (!t) continue;
        if (t.startsWith('@')) continue;
        if (/subscriber/i.test(t)) subscribers = t;
        else if (/video/i.test(t)) videoCount = t;
      }
    }
    const dp = findAll(pageHeader, 'descriptionPreviewViewModel', 10, 2)[0];
    description = (dp && pathStr(dp, 'description', 'content')) || null;
  }

  const c4 = path(root, 'header', 'c4TabbedHeaderRenderer');
  if (c4) {
    if (!name) { const t = path(c4, 'title'); name = (t && str(t, 'simpleText')) || (t && runsText(t)); }
    if (!avatar) avatar = lastThumbUrl(path(c4, 'avatar'));
    if (!subscribers) { const s = path(c4, 'subscriberCountText'); subscribers = (s && str(s, 'simpleText')) || (s && runsText(s)); }
    if (!videoCount) { const s = path(c4, 'videosCountText'); videoCount = (s && str(s, 'simpleText')) || (s && runsText(s)); }
    if (!description) description = pathStr(c4, 'tagline', 'channelTaglineRenderer', 'content');
  }

  const micro = path(root, 'microformat', 'microformatDataRenderer');
  if (!name) name = micro && pathStr(micro, 'title');
  if (!description) description = micro && pathStr(micro, 'description');
  if (!avatar) avatar = lastThumbUrl(micro && path(micro, 'thumbnail'));
  let channelId = requestedId;
  const canonical = micro && pathStr(micro, 'urlCanonical');
  if (canonical) {
    const last = canonical.split('/').pop();
    if (last && last.startsWith('UC')) channelId = last;
  }

  const cleanName = (name || '').trim();
  return {
    channelId,
    name: cleanName || 'Channel',
    avatar: avatar || undefined,
    subscribersText: subscribers || undefined,
    videoCountText: videoCount || undefined,
    description: (description || '').trim() || undefined,
  };
}

function parseAboutToken(root, channelName) {
  const panels = findAll(root, 'engagementPanelSectionListRenderer', 14, 10);
  if (!panels.length) return null;
  const title = (p) => pathStr(p, 'header', 'engagementPanelTitleHeaderRenderer', 'title', 'simpleText');
  const panel = panels.find((p) => title(p) === channelName)
    || panels.find((p) => { const t = title(p); return t && t !== 'Featured Channels'; });
  if (!panel) return null;
  for (const cc of findAll(panel, 'continuationCommand', 14, 6)) {
    const t = pathStr(cc, 'token');
    if (t) return t;
  }
  return null;
}

function parseChannelAbout(root) {
  const vm = findAll(root, 'aboutChannelViewModel', 16, 3)[0];
  if (!vm) return null;
  const links = [];
  for (const el of (arr(vm, 'links') || [])) {
    const link = obj(el, 'channelExternalLinkViewModel');
    if (!link) continue;
    const title = (pathStr(link, 'title', 'content') || '').trim();
    const url = pathStr(link, 'link', 'commandRuns', 0, 'onTap', 'innertubeCommand', 'urlEndpoint', 'url')
      || pathStr(link, 'link', 'commandRuns', 0, 'onTap', 'innertubeCommand', 'commandMetadata', 'webCommandMetadata', 'url');
    if (!url || !title) continue;
    if (!links.some((l) => l.url === url)) links.push({ title, url });
  }
  const text = (...keys) => (pathStr(vm, ...keys) || '').trim() || null;
  const canonical = text('canonicalChannelUrl');
  return {
    description: text('description'),
    country: text('country'),
    subscribersText: text('subscriberCountText'),
    videoCountText: text('videoCountText'),
    viewCountText: text('viewCountText'),
    joinedText: text('joinedDateText', 'content'),
    canonicalUrl: canonical ? canonical.replace(/^http:\/\//, 'https://') : null,
    links,
  };
}

function parseChannelPage(root, requestedId) {
  const channel = parseChannelHeader(root, requestedId);
  const contents = feedItems(root);
  const videos = [];
  let continuation = null;
  for (const entry of contents) {
    const item = obj(entry, 'richItemRenderer');
    if (item) {
      const content = field(item, 'content');
      const v = content && parseAnyVideo(content);
      if (v) videos.push(v);
      continue;
    }
    const gv = obj(entry, 'gridVideoRenderer');
    if (gv) { const v = parseVideoRenderer(gv); if (v) videos.push(v); continue; }
    const cont = obj(entry, 'continuationItemRenderer');
    if (cont) continuation = extractContinuation(cont) || continuation;
  }
  if (!videos.length) {
    for (const gv of findAll(root, 'gridVideoRenderer', 12, 60)) {
      const v = parseVideoRenderer(gv);
      if (v) videos.push(v);
    }
  }
  if (!continuation) continuation = feedContinuation(root);
  // Videos on a channel page don't repeat the owner in each item; stamp the
  // header channel so cards show the real name and avatar.
  const stamped = videos.map((v) => (v.channelName && v.channelAvatar && v.channelId) ? v : {
    ...v,
    channelId: v.channelId || channel.channelId,
    channelName: v.channelName || (channel.name !== 'Channel' ? channel.name : undefined),
    channelAvatar: v.channelAvatar || channel.avatar,
  });
  return {
    channel,
    videos: stamped,
    continuation,
    playlists: [],
    aboutToken: parseAboutToken(root, channel.name),
  };
}

function playlistContinuation(root) {
  for (const grid of findAll(root, 'gridRenderer', 24, 6)) {
    const items = arr(grid, 'items');
    if (!items) continue;
    const hasPlaylists = items.some((item) =>
      (obj(item, 'lockupViewModel') || {}).contentType?.includes('PLAYLIST'));
    if (!hasPlaylists) continue;
    for (const item of items) {
      const cont = obj(item, 'continuationItemRenderer');
      const t = cont && extractContinuation(cont);
      if (t) return t;
    }
  }
  const lockups = findAll(root, 'lockupViewModel', 24, 90);
  const hasPlaylists = lockups.some((l) => (l.contentType || '').includes('PLAYLIST'));
  if (!hasPlaylists) return null;
  const tokens = findAll(root, 'continuationItemRenderer', 24, 10)
    .map((c) => extractContinuation(c)).filter(Boolean);
  return tokens.length ? tokens[tokens.length - 1] : null;
}

function parseChannelPlaylistsPage(root) {
  const out = [];
  for (const pl of findAll(root, 'gridPlaylistRenderer', 24, 60)) {
    const p = parsePlaylistRenderer(pl);
    if (p) out.push(p);
  }
  // Playlist lockups sit behind several renderer/array layers (~16 deep),
  // deeper than the video scans; a shallow bound silently returned none.
  for (const lockup of findAll(root, 'lockupViewModel', 24, 90)) {
    if ((lockup.contentType || '').includes('PLAYLIST')) {
      const p = parsePlaylistLockup(lockup);
      if (p) out.push(p);
    }
  }
  const seen = new Set();
  const playlists = out.filter((p) => (seen.has(p.playlistId) ? false : (seen.add(p.playlistId), true)));
  return { playlists, continuation: playlistContinuation(root), hasTab: true };
}

function parseChannelTabs(root) {
  return findAll(root, 'tabRenderer', 14, 24)
    .map((tab) => {
      const title = pathStr(tab, 'title') || pathStr(tab, 'title', 'content');
      if (!title) return null;
      return { title, params: pathStr(tab, 'endpoint', 'browseEndpoint', 'params') || null };
    })
    .filter(Boolean);
}

// ---- watch page ---------------------------------------------------------------------

function extractLikes(scope) {
  for (const ad of findAll(scope, 'accessibilityData', 12, 60)) {
    const label = pathStr(ad, 'label');
    if (!label) continue;
    const m = LIKES_ALONG_RE.exec(label);
    if (m) return m[1];
  }
  for (const btn of findAll(scope, 'segmentedLikeDislikeButtonViewModel', 12, 4)) {
    for (const tb of findAll(btn, 'toggleButtonViewModel', 8, 8)) {
      const text = pathStr(tb, 'defaultButtonViewModel', 'buttonViewModel', 'title')
        || pathStr(tb, 'toggledButtonViewModel', 'buttonViewModel', 'title');
      if (text && /[0-9]/.test(text)) return text;
    }
  }
  return null;
}

function parseChapters(root) {
  const bar = findAll(root, 'multiMarkersPlayerBarRenderer', 12, 4)[0];
  if (!bar) return [];
  const maps = arr(bar, 'markersMap');
  if (!maps) return [];
  const withChapters = maps.filter((m) => Array.isArray(path(m, 'value', 'chapters')));
  const chosen = withChapters.find((m) => m.key === 'DESCRIPTION_CHAPTERS') || withChapters[0];
  if (!chosen) return [];
  const items = path(chosen, 'value', 'chapters') || [];
  const out = [];
  for (const el of items) {
    const ch = obj(el, 'chapterRenderer');
    if (!ch) continue;
    const titleEl = path(ch, 'title');
    const title = ((titleEl && runsText(titleEl)) || (titleEl && str(titleEl, 'simpleText')) || '').trim();
    const start = Number(pathStr(ch, 'timeRangeStartMillis'));
    if (!title || !Number.isFinite(start) || start < 0) continue;
    out.push({ title, startMs: start });
  }
  const seen = new Set();
  return out.filter((c) => (seen.has(c.startMs) ? false : (seen.add(c.startMs), true)))
    .sort((a, b) => a.startMs - b.startMs);
}

function parseWatch(root, videoId) {
  const primary = findAll(root, 'videoPrimaryInfoRenderer', 10, 2)[0];
  const secondary = findAll(root, 'videoSecondaryInfoRenderer', 10, 2)[0];

  const titleEl = primary && path(primary, 'title');
  const title = ((titleEl && runsText(titleEl)) || (titleEl && str(titleEl, 'simpleText'))
    || pathStr(root, 'contents', 'twoColumnWatchNextResults', 'results', 'results', 'contents', 0,
      'videoPrimaryInfoRenderer', 'title', 'simpleText') || '').trim();

  const viewCountRenderer = primary && path(primary, 'viewCount', 'videoViewCountRenderer');
  const vcEl = viewCountRenderer && path(viewCountRenderer, 'viewCount');
  const svcEl = viewCountRenderer && path(viewCountRenderer, 'shortViewCount');
  const viewsText = (vcEl && runsText(vcEl)) || (vcEl && str(vcEl, 'simpleText'))
    || (svcEl && runsText(svcEl)) || null;
  const publishedText = (primary && (pathStr(primary, 'dateText', 'simpleText') || pathStr(primary, 'relativeDateText', 'simpleText'))) || null;

  let owner = secondary ? path(secondary, 'owner', 'videoOwnerRenderer') : null;
  if (!owner) owner = findAll(root, 'videoOwnerRenderer', 10, 2)[0];
  const ownerTitle = owner && path(owner, 'title');
  const channelName = ((ownerTitle && runsText(ownerTitle)) || (ownerTitle && str(ownerTitle, 'simpleText')) || null);
  const channelId = (owner && pathStr(owner, 'title', 'runs', 0, 'navigationEndpoint', 'browseEndpoint', 'browseId'))
    || (owner && pathStr(owner, 'navigationEndpoint', 'browseEndpoint', 'browseId')) || null;
  const channelAvatar = owner ? lastThumbUrl(path(owner, 'thumbnail')) : null;
  const subsEl = owner && path(owner, 'subscriberCountText');
  const subscribersText = subsEl ? ((str(subsEl, 'simpleText')) || runsText(subsEl)) : null;

  const description = (secondary && pathStr(secondary, 'attributedDescription', 'content'))
    || (secondary && runsText(path(secondary, 'description')))
    || (secondary && pathStr(secondary, 'description', 'simpleText'))
    || null;

  const likesText = extractLikes(primary || root);

  // Members-only videos come back UNPLAYABLE from the logged-out API.
  const playability = path(root, 'playabilityStatus');
  const reason = playability && pathStr(playability, 'reason');
  const membersOnly = Boolean(reason && MEMBERS_RE.test(reason))
    || (primary ? hasMembersBadge(primary, 8) : false)
    || hasMembersBadge(path(root, 'playerOverlays'), 10);

  // related videos from the secondary results
  const related = [];
  let resultList = path(root,
    'contents', 'twoColumnWatchNextResults', 'secondaryResults',
    'secondaryResults', 'results');
  if (!Array.isArray(resultList)) {
    resultList = [];
    for (const sec of findAll(root, 'itemSectionRenderer', 12, 20)) {
      const contents = arr(sec, 'contents');
      if (contents) resultList.push(...contents);
    }
  }
  for (const el of resultList) {
    let matched = false;
    for (const key of ['compactVideoRenderer', 'videoRenderer']) {
      const r = obj(el, key);
      if (r) {
        const v = parseVideoRenderer(r);
        if (v) related.push(v);
        matched = true;
        break;
      }
    }
    if (matched) continue;
    const lv = obj(el, 'lockupViewModel');
    if (lv) { const v = parseLockup(lv); if (v) related.push(v); }
  }

  // The Up next rail ends with a continuation item; keeping its token is what
  // lets the watch page append more recommendations on demand.
  let relatedToken = null;
  for (let i = resultList.length - 1; i >= 0; i -= 1) {
    const cont = obj(resultList[i], 'continuationItemRenderer');
    if (!cont) continue;
    relatedToken = extractContinuation(cont) || null;
    if (relatedToken) break;
  }
  if (!relatedToken) {
    const sec = path(root, 'contents', 'twoColumnWatchNextResults', 'secondaryResults', 'secondaryResults');
    if (sec) {
      for (const cont of findAll(sec, 'continuationItemRenderer', 8, 4)) {
        relatedToken = extractContinuation(cont) || null;
        if (relatedToken) break;
      }
    }
  }

  // comments entry point
  let commentsToken = null;
  let commentsCount = null;
  for (const cp of findAll(root, 'commentsEntryPointHeaderRenderer', 14, 2)) {
    if (!commentsToken) commentsToken = extractContinuation(cp) || pathStr(cp, 'continuationEndpoint', 'continuationCommand', 'token');
    if (!commentsCount) {
      const cc = path(cp, 'commentCount');
      commentsCount = (cc && (runsText(cc) || str(cc, 'simpleText'))) || pathStr(cp, 'commentsCount', 'simpleText');
    }
  }
  if (!commentsToken) {
    for (const cs of findAll(root, 'commentsSectionRenderer', 14, 2)) {
      commentsToken = commentsToken || pathStr(cs, 'continuations', 0, 'continuationEndpoint', 'continuationCommand', 'token');
    }
  }
  if (!commentsToken) {
    for (const p of (Array.isArray(root.engagementPanels) ? root.engagementPanels : [])) {
      const panel = obj(p, 'engagementPanelSectionListRenderer');
      if (!panel) continue;
      const id = pathStr(panel, 'panelIdentifier');
      if (!id || !id.includes('comments')) continue;
      for (const cc of findAll(panel, 'continuationCommand', 12, 2)) {
        commentsToken = commentsToken || pathStr(cc, 'token');
      }
      if (!commentsCount) {
        const ci = path(panel, 'header', 'engagementPanelTitleHeaderRenderer', 'contextualInfo');
        commentsCount = ci ? (runsText(ci) || str(ci, 'simpleText')) : null;
      }
    }
  }

  // Live badge on the primary info (or the "N watching" counter) is
  // authoritative even when the chat panel is collapsed.
  const liveBadge = findAll(primary || root, 'metadataBadgeRenderer', 8, 20)
    .some((badge) => str(badge, 'style') === 'BADGE_STYLE_TYPE_LIVE_NOW'
      || (pathStr(badge, 'label') || '').toUpperCase() === 'LIVE');
  const isLive = liveBadge || (viewCountRenderer && pathStr(viewCountRenderer, 'isLive') === 'true');

  const seenRelated = new Set();
  return {
    videoId,
    title,
    channelId: channelId || null,
    channelName: channelName || null,
    channelAvatar: channelAvatar || null,
    channelVerified: isVerified(owner && owner.badges, owner && owner.attributedTitle) || false,
    subscribersText: subscribersText || null,
    viewsText: viewsText || null,
    publishedText: publishedText || null,
    likesText: likesText || null,
    description: (description || '').trim() || null,
    related: related.filter((v) => (seenRelated.has(v.videoId) ? false : (seenRelated.add(v.videoId), true)))
      .filter((v) => v.videoId !== videoId && !v.membersOnly),
    relatedToken,
    commentsToken,
    commentsCountText: commentsCount || null,
    membersOnly,
    chapters: parseChapters(root),
    isLive: Boolean(isLive),
  };
}

// A continuation of the watch page's secondary results (the Up next rail).
// The appended items are bare lockupViewModel / compactVideoRenderer entries
// inside appendContinuationItemsAction, not wrapped in a rich grid.
function parseWatchContinuation(root) {
  const items = continuationActionItems(root);
  const videos = [];
  let continuation = null;
  for (const el of items) {
    const cont = obj(el, 'continuationItemRenderer');
    if (cont) {
      continuation = extractContinuation(cont) || continuation;
      continue;
    }
    let video = null;
    for (const key of ['compactVideoRenderer', 'videoRenderer', 'playlistVideoRenderer']) {
      const renderer = obj(el, key);
      if (renderer) { video = parseVideoRenderer(renderer); break; }
    }
    if (!video) {
      const lockup = obj(el, 'lockupViewModel');
      if (lockup) video = parseLockup(lockup);
    }
    if (!video) {
      const rich = obj(el, 'richItemRenderer');
      if (rich) {
        const content = field(rich, 'content');
        video = content && parseAnyVideo(content);
      }
    }
    if (video && video.videoId) videos.push(video);
  }
  const seen = new Set();
  return {
    related: videos
      .filter((v) => !v.membersOnly)
      .filter((v) => (seen.has(v.videoId) ? false : (seen.add(v.videoId), true))),
    continuation,
  };
}

// ---- comments ------------------------------------------------------------------------

function collectCommentEntities(root) {
  const out = {};
  const mutations = path(root, 'frameworkUpdates', 'entityBatchUpdate', 'mutations');
  for (const m of Array.isArray(mutations) ? mutations : []) {
    const payload = path(m, 'payload', 'commentEntityPayload');
    if (!payload) continue;
    const id = pathStr(payload, 'properties', 'commentId');
    if (id) out[id] = payload;
  }
  return out;
}

function collectContinuationItems(root) {
  const out = [];
  const endpoints = Array.isArray(root.onResponseReceivedEndpoints) ? root.onResponseReceivedEndpoints : [];
  for (const ep of endpoints) {
    const items = path(ep, 'appendContinuationItemsAction', 'continuationItems')
      || path(ep, 'reloadContinuationItemsCommand', 'continuationItems');
    if (Array.isArray(items)) out.push(...items);
    const cmds = path(ep, 'commandExecutorCommand', 'commands');
    if (Array.isArray(cmds)) {
      for (const cmd of cmds) {
        const ci = path(cmd, 'appendContinuationItemsAction', 'continuationItems')
          || path(cmd, 'reloadContinuationItemsCommand', 'continuationItems');
        if (Array.isArray(ci)) out.push(...ci);
      }
    }
  }
  if (!out.length) {
    for (const ep of (Array.isArray(root.onResponseReceivedActions) ? root.onResponseReceivedActions : [])) {
      const items = path(ep, 'appendContinuationItemsAction', 'continuationItems');
      if (Array.isArray(items)) out.push(...items);
    }
  }
  if (!out.length) {
    // sometimes the section is inlined (initial render)
    findAll(root, 'commentThreadRenderer', 14, 40).forEach((t) => out.push({ commentThreadRenderer: t }));
  }
  return out;
}

function replyToken(th) {
  const replies = field(th, 'replies');
  if (!replies) return null;
  const direct = pathStr(replies,
    'commentRepliesRenderer', 'contents', 0,
    'continuationItemRenderer', 'continuationEndpoint', 'continuationCommand', 'token');
  if (direct) return direct;
  for (const cont of findAll(replies, 'continuationItemRenderer', 8, 4)) {
    const t = extractContinuation(cont);
    if (t) return t;
  }
  return null;
}

function parseCommentThread(th, entities) {
  const vm = path(th, 'commentViewModel', 'commentViewModel') || path(th, 'commentViewModel');
  if (!vm) return null;
  const commentId = pathStr(vm, 'commentId') || pathStr(vm, 'commentKey');
  if (!commentId) return null;
  const entity = entities[commentId];
  const author = (entity && pathStr(entity, 'author', 'displayName'))
    || (pathStr(vm, 'author', 'displayName') || '');
  const avatar = normalizeImageUrl(entity && pathStr(entity, 'author', 'avatarThumbnailUrl'))
    || lastThumbUrl(path(vm, 'author', 'avatar'));
  const contentEl = path(vm, 'content');
  const text = (entity && pathStr(entity, 'properties', 'content', 'content'))
    || (contentEl && str(contentEl, 'content'))
    || (contentEl && runsText(contentEl));
  if (!text) return null;
  const likes = (entity && (pathStr(entity, 'toolbar', 'likeCountNotliked') || pathStr(entity, 'toolbar', 'likeCountLiked')))
    || pathStr(vm, 'likeCount', 'likeCountEntity', 'likeCount')
    || pathStr(vm, 'likeCount', 'simpleText')
    || pathStr(vm, 'voteCount', 'simpleText')
    || null;
  const published = (entity && pathStr(entity, 'properties', 'publishedTime'))
    || pathStr(vm, 'publishedTimeText', 'simpleText')
    || runsText(path(vm, 'publishedTimeText'))
    || pathStr(vm, 'publishedTimeText', 'content')
    || null;
  const rawReplies = (entity && pathStr(entity, 'toolbar', 'replyCount'))
    || pathStr(vm, 'replyCount')
    || pathStr(vm, 'repliesCountText', 'simpleText')
    || null;
  const replies = rawReplies && rawReplies.trim()
    ? (COUNT_RE.test(rawReplies.trim()) ? `${rawReplies.trim()} replies` : rawReplies.trim())
    : null;
  if (!text.trim()) return null;
  return { id: commentId, author, avatar: avatar || null, text, likesText: likes, publishedText: published, replyCountText: replies, repliesToken: replyToken(th) };
}

function parseCommentRenderer(c) {
  const contentEl = path(c, 'contentText');
  const text = (contentEl && runsText(contentEl)) || null;
  if (!text) return null;
  const id = pathStr(c, 'commentId') || `c${Math.abs(hashCode(text))}`;
  const author = pathStr(c, 'authorText', 'simpleText') || '';
  const avatar = lastThumbUrl(path(c, 'authorThumbnail'));
  const likes = pathStr(c, 'voteCount', 'simpleText');
  const published = runsText(path(c, 'publishedTimeText'));
  const replyCount = pathStr(c, 'replyCount');
  return { id, author, avatar, text, likesText: likes, publishedText: published, replyCountText: replyCount ? `${replyCount} replies` : null, repliesToken: null };
}

function hashCode(s) {
  let h = 0;
  for (let i = 0; i < s.length; i += 1) { h = (Math.imul(31, h) + s.charCodeAt(i)) | 0; }
  return h;
}

function parseComments(root) {
  const entities = collectCommentEntities(root);
  const items = collectContinuationItems(root);
  const comments = [];
  let continuation = null;
  for (const el of items) {
    const thread = obj(el, 'commentThreadRenderer');
    if (thread) { const c = parseCommentThread(thread, entities); if (c) { comments.push(c); continue; } }
    const cr = obj(el, 'commentRenderer');
    if (cr) { const c = parseCommentRenderer(cr); if (c) { comments.push(c); continue; } }
    if (obj(el, 'commentViewModel')) { const c = parseCommentThread(el, entities); if (c) { comments.push(c); continue; } }
    const cont = obj(el, 'continuationItemRenderer');
    if (cont) { const t = extractContinuation(cont); if (t) continuation = t; }
  }
  if (!continuation && comments.length) {
    for (const cont of findAll(root, 'continuationItemRenderer', 14, 6)) {
      const t = extractContinuation(cont);
      if (t) { continuation = t; break; }
    }
  }
  return { comments, continuation };
}

// ---- playlists -------------------------------------------------------------------------

function parsePlaylist(root) {
  let title = '';
  let author = null;
  let countText = null;
  const h = findAll(root, 'playlistHeaderRenderer', 12, 2)[0];
  if (h) {
    const titleEl = path(h, 'title');
    title = (titleEl && str(titleEl, 'simpleText')) || (titleEl && runsText(titleEl)) || '';
    author = runsText(path(h, 'ownerText'));
    countText = pathStr(h, 'numVideosText', 'simpleText')
      ? `${pathStr(h, 'numVideosText', 'simpleText')} videos`
      : pathStr(h, 'numVideosText', 'runs', 0, 'text');
  }
  if (!title.trim()) {
    const ph = findAll(root, 'pageHeaderViewModel', 12, 2)[0];
    if (ph) {
      title = pathStr(ph, 'title', 'dynamicTextViewModel', 'text', 'content') || '';
      const rows = path(ph, 'metadata', 'contentMetadataViewModel', 'metadataRows') || [];
      for (const row of Array.isArray(rows) ? rows : []) {
        for (const part of (arr(row, 'metadataParts') || [])) {
          const t = (pathStr(part, 'text', 'content') || '').trim();
          if (!t) continue;
          if (PLAYLIST_COUNT_RE.test(t)) { if (!countText) countText = t; }
          else if (t.toLowerCase() === 'playlist') { /* ignore */ }
          else if (/^by /i.test(t)) { if (!author) author = t.replace(/^by /i, '').trim() || null; }
          else if (VIEWS_RE.test(t)) { /* ignore */ }
          else if (!author) author = t;
        }
      }
    }
  }
  if (!author) {
    const o = findAll(root, 'videoOwnerRenderer', 16, 2)[0];
    if (o) author = ((path(o, 'title') && runsText(path(o, 'title'))) || '').trim() || null;
  }
  if (!title.trim()) {
    const m = findAll(root, 'microformatDataRenderer', 10, 1)[0];
    if (m) title = pathStr(m, 'title') || '';
  }

  // The videos of a playlist are plain video lockups today. The old
  // layout used playlistVideoRenderer items; both are read so a layout
  // switch doesn't empty the screen.
  const legacy = findAll(root, 'playlistVideoRenderer', 16, 200).map((x) => parseVideoRenderer(x)).filter(Boolean);
  const lockups = findAll(root, 'lockupViewModel', 18, 200).map((x) => parseLockup(x)).filter(Boolean);
  const videos = (legacy.length ? legacy : lockups)
    .filter((v, i, list) => list.findIndex((x) => x.videoId === v.videoId) === i);

  let continuation = null;
  for (const cont of findAll(root, 'continuationItemViewModel', 18, 4)) {
    const t = pathStr(cont, 'continuationCommand', 'innertubeCommand', 'continuationCommand', 'token');
    if (t && !continuation) continuation = t;
  }
  for (const cont of findAll(root, 'continuationItemRenderer', 14, 4)) {
    const t = extractContinuation(cont);
    if (t && !continuation) continuation = t;
  }
  return { title: title.trim() || 'Playlist', author, videoCountText: countText, videos, continuation };
}

// ---- transport + client ------------------------------------------------------------------

function defaultConfig() {
  const now = new Date();
  const ymd = `${now.getUTCFullYear()}${String(now.getUTCMonth() + 1).padStart(2, '0')}${String(now.getUTCDate()).padStart(2, '0')}`;
  return {
    key: 'AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8',
    version: `2.${ymd}.00.00`,
    visitor: null,
  };
}

class InnerTube {
  constructor() {
    this.cfg = null;
    this.cache = new Map();
  }

  async config() {
    if (!this.cfg) this.cfg = defaultConfig();
    return this.cfg;
  }

  async refreshConfig() {
    const current = await this.config();
    try {
      const html = await this.rawGet('https://www.youtube.com/');
      let key = current.key;
      let version = current.version;
      let visitor = current.visitor;
      const km = /"INNERTUBE_API_KEY":"([^"]+)"/.exec(html);
      if (km) key = km[1];
      const vm2 = /"INNERTUBE_CONTEXT_CLIENT_VERSION":"([^"]+)"/.exec(html);
      if (vm2) version = vm2[1];
      const dm = /"VISITOR_DATA":"([^"]+)"/.exec(html);
      visitor = dm ? dm[1] : visitor;
      this.cfg = { key, version, visitor };
      return this.cfg;
    } catch {
      return null;
    }
  }

  headers(c) {
    const h = {
      'user-agent': UA_DESKTOP,
      accept: '*/*',
      'accept-language': `${LANG},en;q=0.8`,
      origin: 'https://www.youtube.com',
    };
    if (c) {
      h['x-youtube-client-name'] = '1';
      h['x-youtube-client-version'] = c.version;
      if (c.visitor) h['x-goog-visitor-id'] = c.visitor;
    }
    return h;
  }

  async rawGet(url) {
    const res = await fetch(url, { headers: this.headers(null) });
    if (!res.ok) throw new Error(`YouTube returned HTTP ${res.status}`);
    return res.text();
  }

  async post(endpoint, body) {
    let c = await this.config();
    let lastError = null;
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        const url = `https://www.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false&alt=json&key=${c.key}`;
        const res = await fetch(url, {
          method: 'POST',
          headers: { ...this.headers(c), 'content-type': 'application/json; charset=utf-8' },
          body: JSON.stringify(body),
        });
        if (!res.ok) throw new Error(`YouTube returned HTTP ${res.status}`);
        return await res.json();
      } catch (e) {
        lastError = e;
        if (attempt === 0) {
          const fresh = await this.refreshConfig();
          if (fresh) c = fresh;
          await new Promise((r) => setTimeout(r, 1200));
        }
      }
    }
    throw lastError || new Error('request failed');
  }

  contextOf(c) {
    return {
      client: {
        clientName: 'WEB',
        clientVersion: c.version,
        hl: LANG,
        gl: COUNTRY,
        ...(c.visitor ? { visitorData: c.visitor } : {}),
      },
      user: {},
    };
  }

  async browse(browseId, params = null, continuation = null) {
    const c = await this.config();
    const body = { context: this.contextOf(c) };
    if (continuation) body.continuation = continuation;
    else {
      body.browseId = browseId;
      if (params) body.params = params;
    }
    return this.post('browse', body);
  }

  async cached(key, ttlMs, force, load) {
    if (force) this.cache.delete(key);
    const hit = this.cache.get(key);
    if (hit && Date.now() - hit.at < ttlMs) return hit.data;
    const fresh = await load();
    this.cache.set(key, { at: Date.now(), data: fresh });
    return fresh;
  }

  invalidate() { this.cache.clear(); }

  // ---- home ----

  async home(params = null, force = false) {
    return this.cached(`home:${params || ''}`, 10 * 60_000, force, async () => {
      const feed = parseFeed(await this.browse('FEwhat_to_watch', params));
      return { ...feed, videos: recommendableVideos(feed.videos) };
    });
  }

  async homeContinuation(token) {
    const feed = parseFeed(await this.browse('FEwhat_to_watch', null, token));
    return { ...feed, videos: recommendableVideos(feed.videos) };
  }

  // ---- trending (signed-out FEtrending is dead; use popular search) ----

  async trending(force = false) {
    try {
      return await this.cached(`trending:${COUNTRY}`, 10 * 60_000, force, async () => {
        const results = await this.rawSearch('', TRENDING_PARAMS);
        return {
          chips: [],
          videos: recommendableVideos(results.entries.filter((e) => e.kind === 'video').map((e) => e.video)),
          continuation: results.continuation,
        };
      });
    } catch {
      return { chips: [], videos: [], continuation: null };
    }
  }

  async trendingContinuation(token) {
    try {
      const results = await this.searchContinuation(token);
      return {
        chips: [],
        videos: recommendableVideos(results.entries.filter((e) => e.kind === 'video').map((e) => e.video)),
        continuation: results.continuation,
      };
    } catch {
      return { chips: [], videos: [], continuation: null };
    }
  }

  // ---- search ----

  async rawSearch(query, params) {
    const c = await this.config();
    const body = { context: this.contextOf(c), query };
    if (params) body.params = params;
    const root = await this.post('search', body);
    return parseSearch(root);
  }

  async search(query, filters = {}) {
    const params = buildSearchParams(filters);
    const results = await this.rawSearch(query, params);
    if (results.entries.length) return results;
    const recovered = await this.recoverEmptySearch(query.trim(), filters, params, results);
    return recovered || results;
  }

  // Age-gate recovery, same two steps as the Android client: a channel
  // entity on the completion endpoint, else a retry with the top suggestion.
  async recoverEmptySearch(query, filters, params, empty) {
    if (!query || !empty.restricted) return null;
    try {
      const data = await this.suggestionData(query, false);
      const suggested = data.channels[query.toLowerCase()];
      if (suggested) {
        let page = null;
        try { page = await this.channelVideos(suggested.channelId); } catch { /* offline */ }
        const base = page ? page.channel : { channelId: suggested.channelId, name: '' };
        const resolved = {
          ...base,
          name: (base.name && base.name.trim()) ? base.name : suggested.name,
          avatar: (base.avatar && base.avatar.trim()) ? base.avatar : suggested.avatar,
        };
        const playlists = filters.type === 3
          ? await this.channelPlaylists(suggested.channelId).catch(() => [])
          : [];
        const entries = fallbackSearchEntries(resolved, page ? page.videos : [], playlists, filters.type, filters.duration);
        if (entries.length) return { entries, continuation: null, restricted: false };
      }
      const retry = data.suggestions.find((s) => s && s.toLowerCase() !== query.toLowerCase());
      if (!retry) return null;
      const retried = await this.rawSearch(retry, params);
      return retried.entries.length ? retried : null;
    } catch {
      return null;
    }
  }

  async searchContinuation(token) {
    const c = await this.config();
    const root = await this.post('search', { context: this.contextOf(c), continuation: token });
    return parseSearch(root);
  }

  async suggestionData(query, force = false) {
    const q = (query || '').trim();
    if (!q) return { suggestions: [], channels: {} };
    return this.cached(`suggest:${q}`, 5 * 60_000, force, async () => {
      try {
        const url = 'https://suggestqueries-clients6.youtube.com/complete/search'
          + `?client=youtube&ds=yt&hl=${LANG}&gl=${COUNTRY}`
          + `&q=${encodeURIComponent(q)}`;
        const text = await this.rawGet(url);
        return parseSuggestionData(text);
      } catch {
        return { suggestions: [], channels: {} };
      }
    });
  }

  async suggestions(query, force = false) {
    return (await this.suggestionData(query, force)).suggestions;
  }

  // ---- channel ----

  async channelVideos(channelId, params = CHANNEL_TAB_VIDEOS, force = false) {
    return this.cached(`channel:${channelId}:${params || ''}`, 10 * 60_000, force, async () =>
      parseChannelPage(await this.browse(channelId, params), channelId));
  }

  async channelContinuation(token, channelId = '') {
    return parseChannelPage(await this.browse('', null, token), channelId);
  }

  async channelLive(channelId, force = false) {
    return this.cached(`channelLive:${channelId}`, 10 * 60_000, force, async () =>
      parseChannelPage(await this.browse(channelId, CHANNEL_TAB_LIVE), channelId));
  }

  async channelTabs(channelId, force = false) {
    return this.cached(`channelTabs:${channelId}`, 30 * 60_000, force, async () =>
      parseChannelTabs(await this.browse(channelId)));
  }

  async channelPlaylistsPage(channelId, force = false) {
    try {
      return await this.cached(`channelPlaylistsPage:${channelId}`, 30 * 60_000, force, async () => {
        const tabs = await this.channelTabs(channelId, force);
        const tab = tabs.find((t) => t.title === 'Playlists');
        if (!tab) return { playlists: [], continuation: null, hasTab: false };
        return parseChannelPlaylistsPage(await this.browse(channelId, tab.params || CHANNEL_TAB_PLAYLISTS));
      });
    } catch {
      return { playlists: [], continuation: null, hasTab: true };
    }
  }

  async channelPlaylists(channelId, force = false) {
    return (await this.channelPlaylistsPage(channelId, force)).playlists;
  }

  async channelPlaylistsContinuation(token) {
    return parseChannelPlaylistsPage(await this.browse('', null, token));
  }

  async channelAbout(channelId, token = null, force = false) {
    try {
      return await this.cached(`channelAbout:${channelId}`, 30 * 60_000, force, async () => {
        let aboutToken = token;
        if (!aboutToken) {
          const root = await this.browse(channelId, CHANNEL_TAB_VIDEOS);
          aboutToken = parseAboutToken(root, parseChannelHeader(root, channelId).name);
        }
        if (!aboutToken) return null;
        return parseChannelAbout(await this.browse('', null, aboutToken));
      });
    } catch {
      return null;
    }
  }

  // ---- watch ----

  async watch(videoId) {
    const c = await this.config();
    const root = await this.post('next', { context: this.contextOf(c), videoId });
    return parseWatch(root, videoId);
  }

  async watchContinuation(token) {
    const c = await this.config();
    const root = await this.post('next', { context: this.contextOf(c), continuation: token });
    return parseWatchContinuation(root);
  }

  // ---- comments ----

  async comments(token) {
    const c = await this.config();
    const root = await this.post('next', { context: this.contextOf(c), continuation: token });
    return parseComments(root);
  }

  // ---- playlists ----

  async playlist(playlistId) {
    const browseId = playlistId.startsWith('VL') ? playlistId : `VL${playlistId}`;
    return parsePlaylist(await this.browse(browseId));
  }

  async playlistContinuation(token) {
    return parsePlaylist(await this.browse('', null, token));
  }
}

module.exports = {
  InnerTube,
  // pure parsers for tests
  buildSearchParams,
  parseFeed,
  parseSearch,
  parseSuggestionData,
  fallbackSearchEntries,
  parseChannelPage,
  parseChannelHeader,
  parseChannelAbout,
  parseChannelPlaylistsPage,
  parseChannelTabs,
  parseWatch,
  parseWatchContinuation,
  parseChapters,
  parseComments,
  parsePlaylist,
  parseLockup,
  parseVideoRenderer,
  parsePlaylistLockup,
  normalizeImageUrl,
  parseDuration,
  compactCount,
  hasMembersBadge,
  isShortish,
  recommendableVideos,
  extractContinuation,
  TRENDING_PARAMS,
  CHANNEL_TAB_VIDEOS,
  CHANNEL_TAB_LIVE,
  CHANNEL_TAB_PLAYLISTS,
};
