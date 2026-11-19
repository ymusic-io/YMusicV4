# `:image-loader`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

Our own simple Compose image loader (jvm + android + compose-iOS targets).

- **ImageRequest** — `ImageRequest(key, sources, size, scale)`: `key` is the image's **identity** (every cache layer
  keys by it, never by a url); `sources` are its variants (`ImageSource(url, width, height, crop)` — declared sizes
  are hints); `scale` is `Cover` (fill the box, for cropped views) or `Fit`. `ImageRequest.of(url, w, h)` is a plain
  single-url image whose identity is `imageCacheKey(url)` (the url minus ytimg's rotating `rs` signature; `sqp`
  selects the image and stays). The url-only overloads of `load`/`peek`/`loadOrNull`/`loadFile` use it.

- **ImageLoader** — `ImageLoader(fetchers, scaledCache?, …, variantIndex)`: **everything is a fetcher**
  (`ImageFetcher.fetch(uri, size, scale): DecodedImage?`, first non-null wins). Lookup for a request:
  1. memory, at the 64px size bucket — or any larger cached decode of the same identity;
  2. the scaled disk cache at that bucket;
  3. the sources: those covering the box first (already-fetched before untried), then the largest smaller ones;
     sources the `VariantIndex` knows are missing are skipped; each fetch records its measured size;
  4. if every source fails (offline), the largest cached decode of the identity, not cached under the new size.

  Concurrent loads of one key+size share one fetch; a NotFound source is not retried for 10 minutes in-process.
  `prefetch(request)` warms a request in the background.

- **Stand-ins** — a fetcher may answer with an image it drew instead of failing (the app's `art:` fallback art):
  `DecodedImage.retention` `Memory` keeps it in the memory LRU only, `None` caches it nowhere (a failure that may
  pass, e.g. offline). Neither reaches the scaled cache or the variant index; `loadFile` serves one from
  `ScaledImageCache.transientFile` (`<scaled dir>-transient`, cleared at the first write of a process).

- **Failures** — `ImageLoadException(reason)`: `NotFound` (404/410, absent file or picture), `Network`, `Undecodable`
  (incl. the byte cap), `Unsupported` (no fetcher). Use `decodeChecked` in custom fetchers to classify decode errors.

- **Fetchers**:
  - `networkFetcher(client, fs, cacheDir)` = the Coil/Glide model on kmpHttp's `DiskLruCache` (body streams to disk
    under a byte cap, decode **from the file**, keyed by `imageCacheKey(url)`; the client carries NO http cache).
  - `fileFetcher()` = `file://`.
  - Custom schemes (ffmpeg album art, content providers) plug in with the public `decodeScaled`/`decodeScaledFile`
    helpers; `ImageSourceCache` keeps expensive extractions on disk (`sourceCacheKey(fingerprint)`).

- **Variant index** — `VariantIndex`: per identity, each source's measured size (after crop) and which sources
  answered NotFound (trusted 3 days). `DiskVariantIndex` persists it (small journaled LRU); `MemoryVariantIndex` is
  the in-process default.

- **Scaled decode + caches** — decodes **scaled to cover (or fit) the view size** (Android subsampled
  `BitmapFactory`, Skia draw-down on desktop/iOS via a shared `skikoMain` source set — the full-size image never
  stays resident), memory-LRUs decoded bitmaps, and persists scaled copies that are smaller than their source as
  lossy WebP (`ScaledImageCache` / `DiskScaledImageCache`, versioned key) so warm starts skip fetch + full decode.
  `DecodedImage` carries the source size; `downscaled` is derived from it. `SourceCrop.Letterbox16x9` takes the
  middle 16:9 band of a 4:3 frame (YouTube's hqdefault/sddefault).

- **Concurrency** — two **LIFO gates**: http(s) sources and local ones (files, `art:`) get separate pools, so a slow
  download never holds up a local cover. Newest request first; a load whose every caller cancelled frees its slot.

- **Memory budget** — `defaultMemoryCacheBudget()`: desktop ~1/128 of physical RAM (16–30MB; 30MB when the jlinked
  runtime lacks `jdk.management`), Android a slice of the heap class (16–256MB).

- **AsyncImage** — `AsyncImage(loader, url, …)` or `AsyncImage(loader, key, sources, …)` measures itself and loads
  at its own pixel size; `ContentScale.Fit`/`Inside` decode to fit, everything else to cover.
  - Shows `placeholder` while loading and `error` (→ placeholder fallback) on failure.
  - Draws a memory-cached image as soon as its size is known, via a synchronous `peek` (no placeholder flash on
    scroll-back); a failure is a logged breadcrumb, never thrown into composition.
  - A resize reloads only on ≥100px growth after a 400ms settle — never per drag frame.
  - `crossfadeMs` (off by default) fades an image that arrives late in over the placeholder.
