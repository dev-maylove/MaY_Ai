# Path resource gambar MaY_Ai

Semua path relatif terhadap root module `app/`.

## Icon launcher (home screen)

| Path | Keterangan |
|------|------------|
| `src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon (API 26+) |
| `src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Adaptive icon bulat |
| `src/main/res/drawable/ic_launcher_background.xml` | Layer background hitam |
| `src/main/res/drawable/ic_launcher_foreground_lotus.xml` | Inset wrapper foreground |
| `src/main/res/drawable/ic_launcher_foreground_lotus_img.webp` | Art lotus foreground |
| `src/main/res/mipmap-mdpi/ic_launcher.webp` | 48×48 |
| `src/main/res/mipmap-hdpi/ic_launcher.webp` | 72×72 |
| `src/main/res/mipmap-xhdpi/ic_launcher.webp` | 96×96 |
| `src/main/res/mipmap-xxhdpi/ic_launcher.webp` | 144×144 |
| `src/main/res/mipmap-xxxhdpi/ic_launcher.webp` | 192×192 |
| `src/main/res/mipmap-*/ic_launcher_round.webp` | Sama, varian round |

Sumber desain asli (tidak di-commit ke APK): lampiran `635611.png`.

## Splash screen

| Path | Keterangan |
|------|------------|
| `src/main/res/layout/activity_splash.xml` | Layout default (portrait phone) |
| `src/main/res/layout-land/activity_splash.xml` | Landscape |
| `src/main/res/layout-sw600dp/activity_splash.xml` | Tablet (≥600dp) |
| `src/main/res/drawable/splash_window_background.xml` | Cold-start window (sebelum inflate) |
| `src/main/res/drawable/splash_icon.webp` | Icon di window background |
| `src/main/res/drawable/splash_scrim.xml` | Gradient bawah agar teks terbaca |
| `src/main/res/drawable/splash_lotus.webp` | Art portrait baseline |
| `src/main/res/drawable/splash_lotus_land.webp` | Art landscape baseline |
| `src/main/res/drawable-hdpi/splash_lotus.webp` | Portrait hdpi |
| `src/main/res/drawable-hdpi/splash_lotus_land.webp` | Landscape hdpi |
| `src/main/res/drawable-xhdpi/splash_lotus.webp` | Portrait xhdpi |
| `src/main/res/drawable-xhdpi/splash_lotus_land.webp` | Landscape xhdpi |
| `src/main/res/drawable-xxhdpi/splash_lotus.webp` | Portrait xxhdpi |
| `src/main/res/drawable-xxhdpi/splash_lotus_land.webp` | Landscape xxhdpi |
| `src/main/java/id/mayai/assistant/SplashActivity.kt` | Logika animasi + navigasi |
| `src/main/res/values/themes.xml` → `Theme.MaYAi.Splash` | Theme splash |

Sumber desain asli: lampiran `635610.jpg`.

## Referensi di kode / manifest

- `AndroidManifest.xml` → `android:icon="@mipmap/ic_launcher"`
- `AndroidManifest.xml` → `android:roundIcon="@mipmap/ic_launcher_round"`
- `AndroidManifest.xml` → activity `.SplashActivity` theme `@style/Theme.MaYAi.Splash`
- Layout → `@drawable/splash_lotus` / `@drawable/splash_lotus_land`

## Format & optimasi

- Icon & splash memakai **WebP**.
- Total asset gambar ≈ **227 KB** (sebelum optimasi ~1.5 MB).
- Sistem memilih density folder otomatis.


## Catatan sumber splash
Screenshot HP (`635610.jpg`) sudah dibersihkan: status bar/jam/sinyal di-crop, lotus di-center di kanvas hitam 1080×1920.
