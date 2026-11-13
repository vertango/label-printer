// Label Printer service worker.
// 1. Receives labels shared from other apps (Android share menu -> "Label Printer"),
//    stores them in IndexedDB and opens the app with ?shared=1 so it can pick them up.
// 2. Keeps a copy of the app so it opens quickly and works without internet.
const CACHE = "label-printer-v21";
const FILES = ["./", "./index.html", "./manifest.webmanifest", "./icons/icon-192.png", "./icons/icon-512.png",
  "./vendor/pdfjs/pdf.min.mjs", "./vendor/pdfjs/pdf.worker.min.mjs", "./vendor/pako/pako_deflate.min.js"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

function idb() {
  return new Promise((res, rej) => {
    const r = indexedDB.open("label-printer", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("shared", { autoIncrement: true });
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
}
async function receiveShare(request) {
  const form = await request.formData();
  // Take files from any field (not only "labels"), and note what arrived for troubleshooting.
  const files = [], seen = [];
  for (const [k, v] of form.entries()) {
    if (v && typeof v === "object" && "size" in v) { files.push(v); seen.push(`${k}: file ${v.type || "?"} ${v.size} bytes`); }
    else seen.push(`${k}: text (${String(v).length} chars)`);
  }
  const db = await idb();
  await new Promise((res, rej) => {
    const tx = db.transaction("shared", "readwrite"), st = tx.objectStore("shared");
    for (const f of files) st.add({ file: f, name: f.name, type: f.type });
    st.add({ diag: seen.join("; ") || "empty form", at: Date.now() });
    tx.oncomplete = res; tx.onerror = () => rej(tx.error);
  });
  return Response.redirect(new URL("./?shared=1", self.registration.scope).href, 303);
}

self.addEventListener("fetch", e => {
  const url = new URL(e.request.url);
  if (e.request.method === "POST" && url.pathname.endsWith("/share-target")) {
    e.respondWith(receiveShare(e.request).catch(() => Response.redirect(new URL("./?shared=1", self.registration.scope).href, 303)));
    return;
  }
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  // Network first (so updates show straight away), cached copy when offline.
  // cache: "no-cache" makes the browser check GitHub for a newer copy instead of reusing
  // its own 10-minute copy, so updates show the next time the app opens.
  e.respondWith(fetch(e.request, { cache: "no-cache" }).then(r => {
    if (r.ok) { const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy)); }
    return r;
  }).catch(() => caches.match(e.request, { ignoreSearch: true }).then(r => r || caches.match("./index.html"))));
});
