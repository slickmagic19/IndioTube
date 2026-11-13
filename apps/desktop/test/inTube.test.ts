// InnerTube parser port tests. The params vectors are copied from the
// Android SearchParamsTest (read off the live WEB client), pinning the same
// protobuf encodings on both platforms.
import { describe, expect, it } from 'vitest';
import inTube from '../electron/inTube';

const {
  buildSearchParams, parseVideoRenderer, parseLockup, parseSearch, parseFeed,
  parseWatch, parseChapters, parsePlaylist, parseSuggestionData,
  normalizeImageUrl, parseDuration, fallbackSearchEntries,
} = inTube as any;

describe('buildSearchParams parity with Android', () => {
  it('type filters', () => {
    expect(buildSearchParams({ type: 1 })).toBe('EgIQAQ==');
    expect(buildSearchParams({ type: 2 })).toBe('EgIQAg==');
    expect(buildSearchParams({ type: 3 })).toBe('EgIQAw==');
  });
  it('sort', () => {
    expect(buildSearchParams({ sort: 1 })).toBe('CAE=');
    expect(buildSearchParams({ sort: 2 })).toBe('CAI=');
    expect(buildSearchParams({ sort: 3 })).toBe('CAM=');
  });
  it('upload date combined with type', () => {
    expect(buildSearchParams({ type: 1, uploadDate: 2 })).toBe('EgQIAhAB');
    expect(buildSearchParams({ type: 2, uploadDate: 3 })).toBe('EgQIAxAC');
  });
  it('duration keeps the odd short=1 long=2 medium=3 order', () => {
    expect(buildSearchParams({ duration: 1 })).toBe('EgIYAQ==');
    expect(buildSearchParams({ duration: 2 })).toBe('EgIYAg==');
    expect(buildSearchParams({ duration: 3 })).toBe('EgIYAw==');
  });
  it('chains sort + filters', () => {
    expect(buildSearchParams({ type: 1, sort: 3 })).toBe('CAMSAhAB');
  });
  it('no filters means no params', () => {
    expect(buildSearchParams({})).toBe(null);
  });
});

describe('video parsing', () => {
  const videoRenderer = {
    videoId: 'abc12345678',
    title: { runs: [{ text: 'A great ' }, { text: 'video' }] },
    lengthText: { simpleText: '12:34' },
    viewCountText: { simpleText: '1.2M views' },
    publishedTimeText: { simpleText: '3 days ago' },
    ownerText: { runs: [{ text: 'Test Channel', navigationEndpoint: { browseEndpoint: { browseId: 'UCtest' } } }] },
    thumbnail: { thumbnails: [{ url: '//i.ytimg.com/vi/abc12345678/hqdefault.jpg', width: 480, height: 270 }] },
    badges: [{ metadataBadgeRenderer: { style: 'BADGE_STYLE_TYPE_LIVE_NOW', label: 'LIVE' } }],
  };

  it('maps a videoRenderer to a VideoItem', () => {
    const v = parseVideoRenderer(videoRenderer);
    expect(v.videoId).toBe('abc12345678');
    expect(v.title).toBe('A great video');
    expect(v.durationSec).toBe(12 * 60 + 34);
    expect(v.channelName).toBe('Test Channel');
    expect(v.channelId).toBe('UCtest');
    expect(v.thumbnail).toBe('https://i.ytimg.com/vi/abc12345678/hqdefault.jpg');
    expect(v.isLive).toBe(true);
    expect(v.membersOnly).toBe(false);
  });

  it('drops Shorts (portrait thumbnails) and upcoming premieres', () => {
    const short = { ...videoRenderer, thumbnail: { thumbnails: [{ url: 'x', width: 135, height: 240 }] } };
    expect(parseVideoRenderer(short)).toBe(null);
    expect(parseVideoRenderer({ ...videoRenderer, upcomingEventData: {} })).toBe(null);
    expect(parseVideoRenderer({ ...videoRenderer, title: { simpleText: '' } })).toBe(null);
  });

  it('tags members-only badges', () => {
    const v = parseVideoRenderer({
      ...videoRenderer,
      badges: [{ metadataBadgeRenderer: { label: 'Members only' } }],
    });
    expect(v.membersOnly).toBe(true);
  });

  it('parses a lockupViewModel with metadata rows', () => {
    const lockup = {
      contentType: 'lockupContentTypeViewModelTypeVIDEO',
      contentId: 'vid12345678',
      metadata: {
        lockupMetadataViewModel: {
          title: { content: 'Lockup video' },
          metadata: {
            contentMetadataViewModel: {
              metadataRows: [
                { metadataParts: [{ text: { content: 'Tech Channel' } }] },
                { metadataParts: [{ text: { content: '21M views' } }, { text: { content: ' Streamed 2 years ago' } }] },
              ],
            },
          },
        },
      },
      contentImage: {
        thumbnailViewModel: {
          image: { sources: [{ url: 'https://i.ytimg.com/vi/vid12345678/hq720.jpg', width: 268, height: 151 }] },
          overlays: [{ thumbnailOverlayBadgeViewModel: { thumbnailBadges: [{ text: '8:15' }] } }],
        },
      },
    };
    // overlay badges sit under thumbnailBadges arrays; walk the same shape
    // findAll does. Give it the direct thumbnailBadgeViewModel form too.
    lockup.contentImage.thumbnailViewModel.overlays = [
      { thumbnailBadgeViewModel: { text: '8:15' } },
    ];
    const v = parseLockup(lockup);
    expect(v.videoId).toBe('vid12345678');
    expect(v.title).toBe('Lockup video');
    expect(v.durationSec).toBe(8 * 60 + 15);
    expect(v.channelName).toBe('Tech Channel');
    expect(v.viewsText).toBe('21M views');
    expect(v.publishedText).toContain('ago');
    expect(v.thumbnail).toBe('https://i.ytimg.com/vi/vid12345678/hq720.jpg');
  });
});

describe('search + feed parsing', () => {
  it('reads videos, channels, playlists and continuation from a search page', () => {
    const root = {
      contents: {
        twoColumnSearchResultsRenderer: {
          primaryContents: {
            sectionListRenderer: {
              contents: [
                {
                  itemSectionRenderer: {
                    contents: [
                      { videoRenderer: { videoId: 'a1', title: { runs: [{ text: 'First' }] } } },
                      { channelRenderer: { channelId: 'UCx', title: { simpleText: 'Chan' }, subscriberCountText: { simpleText: '5 subscribers' } } },
                      { playlistRenderer: { playlistId: 'PLx', title: { simpleText: 'Set' }, videoCountText: { simpleText: '12 videos' } } },
                      { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'CONT' } } } },
                    ],
                  },
                },
              ],
            },
          },
        },
      },
    };
    const r = parseSearch(root);
    expect(r.entries.map((e: any) => e.kind)).toEqual(['video', 'channel', 'playlist']);
    expect(r.continuation).toBe('CONT');
    expect(r.restricted).toBe(false);
  });

  it('flags the age-gate promo as restricted, not empty', () => {
    const root = { contents: { decorations: [{ backgroundPromoRenderer: { messageTexts: [] } }] } };
    expect(parseSearch(root).restricted).toBe(true);
  });

  it('skips Shorts rails and ads when parsing a home feed', () => {
    const root = {
      contents: {
        twoColumnBrowseResultsRenderer: {
          tabs: [{
            tabRenderer: {
              content: {
                richGridRenderer: {
                  contents: [
                    { richItemRenderer: { content: { adSlotRenderer: {} } } },
                    { richItemRenderer: { content: { reelShelfRenderer: {} } } },
                    { richItemRenderer: { content: { videoRenderer: { videoId: 'ok1', title: { runs: [{ text: 'Fine' }] } } } } },
                    { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'NEXT' } } } },
                  ],
                },
              },
            },
          }],
        },
      },
    };
    const feed = parseFeed(root);
    expect(feed.videos.map((v: any) => v.videoId)).toEqual(['ok1']);
    expect(feed.continuation).toBe('NEXT');
  });

  it('suggestion JSONP parses with entity channels', () => {
    const text = 'window.google.ac.h(["quicky",[["quickybabis",[],null],[ "quickybaby",[],{"zav":"UCreal","zao":"Quicky Baby","zai":"//yt3.x/a.jpg"}]],"x"])';
    const d = parseSuggestionData(text);
    expect(d.suggestions[0]).toBe('quickybabis');
    expect(d.channels['quickybaby']).toMatchObject({ channelId: 'UCreal', name: 'Quicky Baby' });
    expect(d.channels['quickybaby'].avatar).toBe('https://yt3.x/a.jpg');
  });

  it('fallback search honors duration filters like Android', () => {
    const channel = { channelId: 'UCtest', name: 'Test Channel' };
    const videos = [
      { videoId: 'short', durationSec: 90 }, { videoId: 'medium', durationSec: 600 }, { videoId: 'long', durationSec: 1800 },
    ];
    const ids = (duration: number) => fallbackSearchEntries(channel, videos, [], 1, duration)
      .filter((e: any) => e.kind === 'video').map((e: any) => e.video.videoId);
    expect(ids(1)).toEqual(['short']);
    expect(ids(3)).toEqual(['medium']);
    expect(ids(2)).toEqual(['long']);
    // no match degrades to the channel itself
    const degraded = fallbackSearchEntries(channel, [{ videoId: 'long', durationSec: 1800 }], [], 1, 1);
    expect(degraded.length).toBe(1);
    expect(degraded[0].channel.channelId).toBe('UCtest');
  });
});

describe('watch parsing', () => {
  const root = {
    contents: {
      twoColumnWatchNextResults: {
        results: { results: { contents: [] } },
        secondaryResults: {
          secondaryResults: {
            results: [
              { compactVideoRenderer: { videoId: 'rel1', title: { runs: [{ text: 'Related one' }] } } },
              { compactVideoRenderer: { videoId: 'rel1', title: { runs: [{ text: 'dup' }] } } },
              { compactVideoRenderer: { videoId: 'MAIN', title: { runs: [{ text: 'self' }] } } },
            ],
          },
        },
      },
    },
    trackingParams: '',
  };
  const withInfo = JSON.parse(JSON.stringify(root));
  withInfo.engagementPanels = [];
  // place primary/secondary info where findAll can find them
  withInfo.contents.twoColumnWatchNextResults.results.results.contents = [
    {
      videoPrimaryInfoRenderer: {
        title: { runs: [{ text: 'Main video' }] },
        viewCount: { videoViewCountRenderer: { viewCount: { simpleText: '9 views' } } },
        dateText: { simpleText: 'Jan 1, 2026' },
      },
    },
    {
      videoSecondaryInfoRenderer: {
        owner: { videoOwnerRenderer: { title: { runs: [{ text: 'Owner' }] }, subscriberCountText: { simpleText: '1 subscriber' } } },
        attributedDescription: { content: 'Description here' },
      },
    },
  ];

  it('maps the watch next response', () => {
    const w = parseWatch(withInfo, 'MAIN');
    expect(w.title).toBe('Main video');
    expect(w.channelName).toBe('Owner');
    expect(w.viewsText).toBe('9 views');
    expect(w.publishedText).toBe('Jan 1, 2026');
    expect(w.description).toBe('Description here');
    // deduped, self-filtered
    expect(w.related.map((v: any) => v.videoId)).toEqual(['rel1']);
  });

  it('reads chapters sorted and deduped', () => {
    const chaptersRoot = {
      playerOverlays: {
        playerLayerRenderer: {
          content: {
            multiMarkersPlayerBarRenderer: {
              markersMap: [
                { key: 'DESCRIPTOR', value: {} },
                {
                  key: 'DESCRIPTION_CHAPTERS',
                  value: {
                    chapters: [
                      { chapterRenderer: { title: { runs: [{ text: 'Second' }] }, timeRangeStartMillis: '20000' } },
                      { chapterRenderer: { title: { runs: [{ text: 'First' }] }, timeRangeStartMillis: '5000' } },
                      { chapterRenderer: { title: { simpleText: '' }, timeRangeStartMillis: '9000' } },
                    ],
                  },
                },
              ],
            },
          },
        },
      },
    };
    expect(parseChapters(chaptersRoot)).toEqual([
      { title: 'First', startMs: 5000 },
      { title: 'Second', startMs: 20000 },
    ]);
  });

  it('playlist page reads lockups and header view models', () => {
    const plRoot = {
      header: {
        playlistHeaderRenderer: {
          title: { simpleText: 'My set' },
          ownerText: { runs: [{ text: 'Owner name' }] },
          numVideosText: { runs: [{ text: '3 videos' }] },
        },
      },
      contents: {
        twoColumnBrowseResultsRenderer: {
          tabs: [{
            tabRenderer: {
              content: {
                sectionListRenderer: {
                  contents: [{ itemSectionRenderer: { contents: [
                    { playlistVideoRenderer: { videoId: 'p1', title: { runs: [{ text: 'One' }] } } },
                    { playlistVideoRenderer: { videoId: 'p2', title: { runs: [{ text: 'Two' }] } } },
                  ] } }],
                },
              },
            },
          }],
        },
      },
    };
    const p = parsePlaylist(plRoot);
    expect(p.title).toBe('My set');
    expect(p.author).toBe('Owner name');
    expect(p.videos.map((v: any) => v.videoId)).toEqual(['p1', 'p2']);
  });
});

describe('small helpers', () => {
  it('normalizeImageUrl fixes protocol-relative and http links', () => {
    expect(normalizeImageUrl('//yt3.ggpht.com/x')).toBe('https://yt3.ggpht.com/x');
    expect(normalizeImageUrl('http://i.ytimg.com/x')).toBe('https://i.ytimg.com/x');
    expect(normalizeImageUrl('')).toBe(null);
  });
  it('parseDuration handles h:mm:ss', () => {
    expect(parseDuration('1:02:03')).toBe(3723);
    expect(parseDuration('4:05')).toBe(245);
    expect(parseDuration(null)).toBe(0);
  });
});

// Verified channels: the badge is the check-circle icon in the owner badges;
// the accessibility label ("Verified") is the fallback marker.
describe('verified channels', () => {
  const base = {
    videoId: 'v1',
    title: { runs: [{ text: 'A video' }] },
    lengthText: { simpleText: '10:00' },
    ownerText: { runs: [{ text: 'A channel', navigationEndpoint: { browseEndpoint: { browseId: 'UC1' } } }] },
  };

  it('flags a renderer whose owner carries the check-circle badge', () => {
    const v = parseVideoRenderer({
      ...base,
      ownerBadges: [{ metadataBadgeRenderer: { icon: { iconType: 'CHECK_CIRCLE_THICK' } } }],
    });
    expect(v.channelVerified).toBe(true);
  });

  it('flags the accessibility-label form too', () => {
    const v = parseVideoRenderer({
      ...base,
      ownerBadges: [{ metadataBadgeRenderer: { accessibilityData: { label: 'Verified' } } }],
    });
    expect(v.channelVerified).toBe(true);
  });

  it('leaves ordinary channels unflagged', () => {
    const v = parseVideoRenderer(base);
    expect(v.channelVerified).toBeUndefined();
  });
});
