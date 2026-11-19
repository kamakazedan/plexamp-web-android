// plexamp-web for Android: what the player's page and the app say to each other.
// The app runs this in the player's own page (its origin, and no other), before
// any of the page's scripts. It uses only what the web app already uses, as in
// any browser:
//
// - What's playing: the page's Media Session (navigator.mediaSession), which a
//   WebView keeps to itself, goes to the app, for the lock screen, the
//   notification, headsets and cars. Their buttons come back to the page's own
//   handlers, as a browser's media keys would.
// - Files the page saves (a playlist to a file) are handed to the app, which
//   asks where to save them.
// - Sharing (navigator.share, which a WebView hasn't got) opens Android's
//   share sheet.
// - Downloads: playlists kept on the phone (window.plexampAndroid.downloads, the
//   one thing here the web app asks for by name: see its js/downloads.js). The
//   page asks the app, and is told as they change.
//
// Nothing else goes to the app, and the app sends nothing but those buttons and
// downloads' answers. How the web app saves and shares (a Blob's link, clicked;
// text, not files) and uses downloads is listed in plexamp-web's
// docs/android-app.md, and its CI fails where it changes.
(() => {
  'use strict';
  if (window.top !== window || window.__plexampAndroid) return;
  Object.defineProperty(window, '__plexampAndroid', { value: true });

  // The app's end, which WebView puts in the page (PageBridge.kt).
  const port = () => globalThis.PlexampAndroid;
  const handlers = new Map();
  let listening = false;

  const listen = () => {
    const p = port();
    if (listening || !p) return;
    listening = true;
    p.onmessage = (event) => {
      let msg;
      try {
        msg = JSON.parse(event.data);
      } catch {
        return;
      }
      if (msg && (msg.type === 'downloads' || msg.type === 'downloads-state')) return onDownloads(msg);
      if (!msg || msg.type !== 'action') return;
      const handler = handlers.get(msg.action);
      if (typeof handler !== 'function') return;
      const details = { action: msg.action };
      if (msg.action === 'seekto') details.seekTime = Math.max(0, Number(msg.seekTime) || 0);
      try {
        handler(details);
      } catch (err) {
        console.error(err);
      }
    };
  };

  const send = (msg) => {
    listen();
    const p = port();
    if (!p) return;
    try {
      p.postMessage(JSON.stringify(msg));
    } catch {
      /* the app has gone */
    }
  };

  // ---------- what's playing ----------

  const text = (v) => (v == null ? '' : String(v));
  const absolute = (src) => {
    try {
      const u = new URL(String(src), location.href);
      return u.protocol === 'https:' || u.protocol === 'http:' ? u.href : null;
    } catch {
      return null;
    }
  };
  // The largest picture up to 512 pixels, or else the smallest.
  const artworkOf = (list) => {
    const items = Array.from(list || [])
      .map((a) => ({
        src: absolute(a && a.src),
        size: Math.max(0, ...text(a && a.sizes).split(/\s+/).map((s) => parseInt(s, 10) || 0)),
      }))
      .filter((a) => a.src);
    if (!items.length) return null;
    const fits = items.filter((a) => a.size && a.size <= 512).sort((x, y) => y.size - x.size);
    if (fits.length) return fits[0].src;
    return items.sort((x, y) => (x.size || Infinity) - (y.size || Infinity))[0].src;
  };

  if (typeof window.MediaMetadata !== 'function') {
    window.MediaMetadata = class MediaMetadata {
      constructor(init = {}) {
        this.title = text(init.title);
        this.artist = text(init.artist);
        this.album = text(init.album);
        this.artwork = Array.from(init.artwork || []).map((a) => ({ src: text(a && a.src), sizes: text(a && a.sizes), type: text(a && a.type) }));
      }
    };
  }

  let metadata = null;
  let playbackState = 'none';
  const session = {
    get metadata() {
      return metadata;
    },
    set metadata(value) {
      metadata = value || null;
      send(
        metadata
          ? { type: 'metadata', title: text(metadata.title), artist: text(metadata.artist), album: text(metadata.album), artwork: artworkOf(metadata.artwork) }
          : { type: 'metadata', empty: true },
      );
    },
    get playbackState() {
      return playbackState;
    },
    set playbackState(value) {
      if (value !== 'none' && value !== 'paused' && value !== 'playing') return;
      playbackState = value;
      send({ type: 'state', state: value });
    },
    setActionHandler(action, handler) {
      if (typeof handler === 'function') handlers.set(String(action), handler);
      else handlers.delete(String(action));
      send({ type: 'actions', actions: [...handlers.keys()] });
    },
    setPositionState(state) {
      if (!state || state.duration === undefined) {
        send({ type: 'position', duration: 0, position: 0, rate: 1 });
        return;
      }
      const duration = Number(state.duration);
      const position = Number(state.position ?? 0);
      const rate = Number(state.playbackRate ?? 1);
      if (!(duration >= 0) || !(position >= 0) || position > duration || !rate) throw new TypeError('Invalid position state');
      send({ type: 'position', duration: Number.isFinite(duration) ? duration : 0, position, rate });
    },
    setMicrophoneActive() {},
    setCameraActive() {},
  };
  try {
    Object.defineProperty(navigator, 'mediaSession', { value: session, configurable: true, enumerable: true });
  } catch {
    /* left as the WebView has it */
  }

  // ---------- files the page saves ----------

  // The page hands a file over as a blob: address it clicks. Its player's policy
  // (connect-src) keeps fetch() from blob: addresses, so the file itself is kept
  // as its address is made, and read from there.
  const blobs = new Map();
  const createObjectURL = URL.createObjectURL;
  const revokeObjectURL = URL.revokeObjectURL;
  URL.createObjectURL = function (object) {
    const url = createObjectURL.call(URL, object);
    if (object instanceof Blob) {
      blobs.set(url, object);
      setTimeout(() => blobs.delete(url), 60000);
    }
    return url;
  };
  URL.revokeObjectURL = function (url) {
    blobs.delete(url);
    return revokeObjectURL.call(URL, url);
  };

  const click = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    const blob = this.hasAttribute('download') && port() ? blobs.get(this.href) : undefined;
    if (!blob) return click.call(this);
    const name = this.getAttribute('download') || 'plexamp-web';
    const reader = new FileReader();
    reader.onload = () =>
      send({ type: 'download', name, mime: blob.type || 'application/octet-stream', data: String(reader.result).split(',')[1] || '' });
    reader.readAsDataURL(blob);
    return undefined;
  };

  // ---------- sharing ----------

  if (typeof navigator.share !== 'function') {
    Object.defineProperty(navigator, 'share', {
      configurable: true,
      value: (data = {}) => {
        const body = [data.text, data.url].filter(Boolean).map(String).join('\n');
        if (!port() || !body) return Promise.reject(new DOMException('Nothing to share.', 'NotAllowedError'));
        send({ type: 'share', title: data.title ? String(data.title) : null, text: body });
        return Promise.resolve();
      },
    });
    Object.defineProperty(navigator, 'canShare', {
      configurable: true,
      value: (data = {}) => !data.files && !!(data.text || data.url),
    });
  }

  // ---------- downloads ----------

  // Each question's answer, by its id; and who's told when downloads change.
  const asked = new Map();
  const watchers = new Set();
  let nextId = 1;

  const ask = (op, args = {}) =>
    new Promise((resolve, reject) => {
      if (!port()) return reject(Object.assign(new Error('gone'), { code: 'gone' }));
      const id = nextId++;
      // (Adding a long playlist asks the server a while: but not for ever.)
      const timer = setTimeout(() => {
        asked.delete(id);
        reject(Object.assign(new Error('timeout'), { code: 'timeout' }));
      }, 180000);
      asked.set(id, { resolve, reject, timer });
      send({ ...args, type: 'downloads', id, op });
    });

  const tell = (state) => {
    for (const fn of watchers) {
      try {
        fn(state);
      } catch (err) {
        console.error(err);
      }
    }
  };

  function onDownloads(msg) {
    if (msg.type === 'downloads-state') return msg.state && tell(msg.state);
    const q = asked.get(msg.id);
    if (!q) return;
    asked.delete(msg.id);
    clearTimeout(q.timer);
    if (!msg.ok) {
      const error = msg.error && typeof msg.error === 'object' ? msg.error : {};
      return q.reject(Object.assign(new Error(String(error.code || 'failed')), error));
    }
    if (msg.state) tell(msg.state);
    q.resolve(msg.state);
  }

  // Where the app answers with what's downloaded: at the player's own address.
  const local = (path) => new URL(`__plexamp-web-android/downloads/${path}`, document.baseURI).href;
  const str = (v) => (v == null ? null : String(v));

  const downloads = Object.freeze({
    state: () => ask('state'),
    add: (playlist) => ask('add', { playlist: String(playlist) }),
    remove: (playlist) => ask('remove', { playlist: String(playlist) }),
    set: (s = {}) => ask('set', { quality: str(s.quality), network: str(s.network), prefer: str(s.prefer) }),
    account: (a = {}) =>
      ask('account', {
        serverId: text(a.serverId),
        token: text(a.token),
        user: text(a.user),
        uris: Array.from(a.uris || []).map(String),
        device: text(a.device),
      }),
    forget: () => ask('forget'),
    bad: (track) => ask('bad', { track: String(track) }),
    // `fn(state)` each time downloads change: returns what stops it.
    watch: (fn) => {
      watchers.add(fn);
      return () => watchers.delete(fn);
    },
    trackUrl: (id) => local(`track/${encodeURIComponent(id)}`),
    playlistUrl: (id) => local(`playlist/${encodeURIComponent(id)}`),
    coverUrl: (path) => local(`cover?path=${encodeURIComponent(path)}`),
  });
  Object.defineProperty(window, 'plexampAndroid', { value: Object.freeze({ downloads }) });

  send({ type: 'hello' });
  document.addEventListener('DOMContentLoaded', listen, { once: true });
})();
