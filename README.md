# MaY_Ai

Asisten AI Android berbahasa Indonesia — **Kotlin**, **Jetpack Compose**, **Room**, **DataStore**, backend lokal `llama.cpp` (OpenAI-compatible).

**Versi 0.2.6**

## Fitur
- UI chat dark neon violet/cyan + adaptive icon
- **Streaming** respons (SSE / `stream: true`) — token muncul real-time
- **Fallback otomatis** ke non-stream jika server tidak support SSE
- **Retry otomatis** (max 3x, backoff 0.8s → 1.6s) untuk timeout/jaringan/502/503/429
- **Deteksi error event SSE** (`event: error`, `{"error":...}`, `finish_reason=error`) — pesan server ditampilkan, tanpa retry sia-sia
- **Multi-session** — drawer kiri, buat/hapus/pilih sesi
- **System prompt** bisa diedit & disimpan
- URL server disimpan (DataStore)
- Riwayat per-sesi (Room, cascade delete)
- Status koneksi + pesan error jelas
- Hapus pesan sesi aktif
- Auto-scroll, IME Send, soft keyboard `adjustResize`
- ProGuard/R8 untuk release
- Gradle Wrapper resmi (`./gradlew`)
- GitHub Actions → APK Debug

## Build

```bash
# Debug
./gradlew :app:assembleDebug

# Release (minified)
./gradlew :app:assembleRelease
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`

## Menjalankan llama-server

```bash
./llama.cpp/build/bin/llama-server \
  -m ./models/may_ai-qwen.gguf \
  --host 0.0.0.0 \
  --port 8080 \
  -c 4096 \
  -t 4
```

Default app: `http://127.0.0.1:8080`  
Perangkat lain di LAN: `http://192.168.x.x:8080`

> Jangan commit file `.gguf`.

## Struktur

```
app/src/main/java/id/mayai/assistant/
  ChatData.kt         # Session + Message Room entities/DAOs
  ChatRepository.kt   # SSE streaming API, DataStore, multi-session
  MainActivity.kt     # Compose UI (drawer, settings, streaming bubble)
```

## Catatan
- DB version 2 (destructive migration dari starter v1).
- Cleartext HTTP hanya untuk lokal; jangan expose server tanpa TLS ke publik.
- Streaming mengandalkan format OpenAI SSE (`data: {...}\n\n` + `[DONE]`).
- Jika stream gagal (server non-stream only / body JSON biasa), app otomatis mengulang request dengan `stream: false`.


## Asset gambar
Lihat daftar path lengkap di [`docs/IMAGE_PATHS.md`](docs/IMAGE_PATHS.md).
Splash adaptif: `layout` / `layout-land` / `layout-sw600dp` + WebP per density.
