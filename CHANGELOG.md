# Changelog

## Unreleased

### NEW
- Windows: YMusic for Windows, x64 and Arm64, with a setup installer, updates from inside the app and hardware video decoding ([e4ca5ebb0](https://github.com/ymusic-io/YMusicV4/commit/e4ca5ebb0ffedb82cb71813cfc2347849f925c7b), [6f99cf173](https://github.com/ymusic-io/YMusicV4/commit/6f99cf1733899aacbbce794be4cd4aa99b8aa127), [32abdcef5](https://github.com/ymusic-io/YMusicV4/commit/32abdcef55f2c25609c9d64d52d6d01477632600))
- Windows: Play/Pause, Stop and Quit from the taskbar Jump List ([f99eb85c1](https://github.com/ymusic-io/YMusicV4/commit/f99eb85c1b185a80c4336c09700648741c083309), [87f4eebbf](https://github.com/ymusic-io/YMusicV4/commit/87f4eebbfaf541bd3d7d0f39a21f14136ef94e8d))
- Desktop: keyboard shortcuts, listed with F1: Ctrl/Cmd+←/→ previous/next, Ctrl/Cmd+Shift+←/→ seek 10 s, Ctrl/Cmd+↑/↓ volume (the new level shows), Ctrl/Cmd+F search, Ctrl/Cmd+1–9 tabs, and Esc twice goes back ([2ffb98e29](https://github.com/ymusic-io/YMusicV4/commit/2ffb98e298fdc0f5e43c0d90e8447607d9a6e86a), [e86708448](https://github.com/ymusic-io/YMusicV4/commit/e867084486a8cafbbf402b2404eecaa9334a339f), [c174a000a](https://github.com/ymusic-io/YMusicV4/commit/c174a000a1aef2b0e2ed5c2d7b7349d3c5e20f87), [ec435d52a](https://github.com/ymusic-io/YMusicV4/commit/ec435d52a2086a7a6e653febab857387778b2d3a), [a62e09569](https://github.com/ymusic-io/YMusicV4/commit/a62e09569ebb4f89c9f4c31bbb7e0558041fdd01), [f41887482](https://github.com/ymusic-io/YMusicV4/commit/f418874821981f4c5c06ca7514ffaad771de603d))
- Desktop: move through the whole app with the keyboard: arrow keys in lists, tab rows, menus and dialogs, and Tab reaches the video controls ([b2070f4bd](https://github.com/ymusic-io/YMusicV4/commit/b2070f4bd1114cbf2a182a7d1f8921297fdf5165), [ef25f28f1](https://github.com/ymusic-io/YMusicV4/commit/ef25f28f1721a59851b9d2a62117c41bfc1557f8), [9d2df9b8d](https://github.com/ymusic-io/YMusicV4/commit/9d2df9b8dc4882cc3aad1e6101cd724124594acb))
- Desktop: keep the window above other windows with the pin button in the title bar ([02cd7a556](https://github.com/ymusic-io/YMusicV4/commit/02cd7a556f08875c32b54d7ae58706244c741685))
- Desktop: Pause/Resume, Stop and Quit from the tray icon's right-click menu, and from the Dock icon's menu on macOS ([eea1a758a](https://github.com/ymusic-io/YMusicV4/commit/eea1a758adfc6f4f1327661ddca523721604dad8))
- Desktop: Reveal in Finder / File Explorer for Library songs and folders ([6202caafa](https://github.com/ymusic-io/YMusicV4/commit/6202caafa9e60db290d8ee99cbd436219be93182))

### FIXED
- 4K YouTube videos lagged the whole device when it couldn't decode AV1 in hardware: YouTube now learns which formats the device plays smoothly and picks one of those ([245f0c801](https://github.com/ymusic-io/YMusicV4/commit/245f0c801d95ff60acd053955007a952660c2c3c), [98a0c0f46](https://github.com/ymusic-io/YMusicV4/commit/98a0c0f467ef771a105d7da01eac95ed13d7f842), [fd2cefe2c](https://github.com/ymusic-io/YMusicV4/commit/fd2cefe2c095a6694bb1fc70c741d83f34851b23))
- A 4K YouTube video played again from the cache could freeze while the sound played on ([3bdc6d33e](https://github.com/ymusic-io/YMusicV4/commit/3bdc6d33e38887c7f2c469eb6999b2ea8313eb62), [1661d6099](https://github.com/ymusic-io/YMusicV4/commit/1661d6099b44f818d3fb1c856d4397c62514b794), [81433c4e3](https://github.com/ymusic-io/YMusicV4/commit/81433c4e3ec74153297e7291ace879ac70b6e8ad), [47fe9b652](https://github.com/ymusic-io/YMusicV4/commit/47fe9b652c229bca1941ee191b25ca4817ae1505))
- A YouTube video could turn black with the sound playing after a quality switch, until you seeked ([3fc1de287](https://github.com/ymusic-io/YMusicV4/commit/3fc1de287855273b51407715c386e49a43fc134b))
- Desktop and iOS: 10-bit (HDR) VP9 videos, from YouTube or from files, played with no picture ([ee3af6a43](https://github.com/ymusic-io/YMusicV4/commit/ee3af6a43fa3368bfaf246915e4a026f31296569), [b58ef31d6](https://github.com/ymusic-io/YMusicV4/commit/b58ef31d6cd0c1e837b6640ad95aab24ec3279db), [0791fb4ea](https://github.com/ymusic-io/YMusicV4/commit/0791fb4eab341411140fa3652f54b0c057360836))
- A damaged frame in a video froze the picture until the next keyframe while the decoder restarted over and over ([851ba6322](https://github.com/ymusic-io/YMusicV4/commit/851ba6322d35d9e0639cf60abbcb1387c7278a0f))
- macOS 13 and older (Apple silicon), 14 and older (Intel): songs and videos didn't play ([c6e913070](https://github.com/ymusic-io/YMusicV4/commit/c6e91307027a0227a371b659dbd0b731fb90cbca))
- Desktop: local 4K AV1 videos dropped every other frame ([9529b1400](https://github.com/ymusic-io/YMusicV4/commit/9529b140060929ee8d987dfe5d22176af7ea7055))
- Desktop: opening YMusic again didn't bring its window to the front when it was behind other apps ([dc038bcd6](https://github.com/ymusic-io/YMusicV4/commit/dc038bcd6d552e7f96e23926d59b1d313c8fd453))
- Desktop: on some computers the app quit right after launching ([b7b9bf892](https://github.com/ymusic-io/YMusicV4/commit/b7b9bf8927a50ee061252633078d4f56770008de))
- Desktop: quitting lost the playing song's watch time and its Play again position ([380bf5e22](https://github.com/ymusic-io/YMusicV4/commit/380bf5e22cc4a7cdcd947d0f23b14f602a436236), [7744b7f6a](https://github.com/ymusic-io/YMusicV4/commit/7744b7f6ae00b20aceab54f3363296490acdbac3))
- Desktop: the cookie password screen was white under the dark theme ([87ee84b5a](https://github.com/ymusic-io/YMusicV4/commit/87ee84b5ad8f307114c38c4fda7b3b22af8374cc))
- Desktop: the tray mini player opened at the top of the main screen; it now opens next to the tray icon, on the screen you clicked ([60936563a](https://github.com/ymusic-io/YMusicV4/commit/60936563a912b73db16424c79ff635cf6e4f167f))

### IMPROVED
- Screen readers read feed cards, song rows, the queue, the seek bar, sliders, chips and Now Playing's buttons in full and once, with their state ([a9da88f16](https://github.com/ymusic-io/YMusicV4/commit/a9da88f1630ada440b1526e83d084a3f227cb601), [68e808dc3](https://github.com/ymusic-io/YMusicV4/commit/68e808dc323e18e35d9b44f04ed1fb9493f6018c), [79eda45ba](https://github.com/ymusic-io/YMusicV4/commit/79eda45ba5a4016e0e37e66f57c6ac92064af609), [22654e720](https://github.com/ymusic-io/YMusicV4/commit/22654e720914b4376fd8e987f70f6aeacde831b8), [00142196f](https://github.com/ymusic-io/YMusicV4/commit/00142196fdf77fe066ade5f72ce368d6383cccd8), [f0ca5b7fc](https://github.com/ymusic-io/YMusicV4/commit/f0ca5b7fce990c9dd2077e46c47ad902c8aad262))
- Macs with Apple silicon decode local VP9 videos in hardware ([5d0ea43d6](https://github.com/ymusic-io/YMusicV4/commit/5d0ea43d61db606acddb6b7f182886067c1f4190))

## 4.0.12

### NEW
- Share YouTube videos, playlists, channels and albums as a link, from their rows, the queue, Now Playing, video details and the playlist page, through the system share sheet on Android and iOS ([#45](https://github.com/ymusic-io/YMusicV4/issues/45), [#19](https://github.com/ymusic-io/YMusicV4/issues/19), [#38](https://github.com/ymusic-io/YMusicV4/issues/38), [#34](https://github.com/ymusic-io/YMusicV4/issues/34), [bb47fdbc3](https://github.com/ymusic-io/YMusicV4/commit/bb47fdbc3996ef21f7f632b34396239c75cca4ad), [1b1f38c54](https://github.com/ymusic-io/YMusicV4/commit/1b1f38c54337a535c24486cc0a3cd80f9490b0f1), [6ab637c6f](https://github.com/ymusic-io/YMusicV4/commit/6ab637c6f428a13bf4cfd34bcdd93fe33bed59c3), [c46da11a5](https://github.com/ymusic-io/YMusicV4/commit/c46da11a5108c5b49dbd80502476cbb23352a170), [057bc5ef4](https://github.com/ymusic-io/YMusicV4/commit/057bc5ef46ad68780ae88594001160b04e5111ac))
- The same song menu in every list: Go to artist, Go to album and Add to playlist in the Library, Library search and the queue; Library search also gets Show in folder and Remove from disk ([#45](https://github.com/ymusic-io/YMusicV4/issues/45), [#55](https://github.com/ymusic-io/YMusicV4/issues/55), [b4038e2d3](https://github.com/ymusic-io/YMusicV4/commit/b4038e2d3c741772790159139a74063137ba4c14))
- Downloads: rename a finished download's file, add it to a playlist, share it, download it in another format, or remove it from disk ([#45](https://github.com/ymusic-io/YMusicV4/issues/45), [824d94527](https://github.com/ymusic-io/YMusicV4/commit/824d9452729f4d98e31bf85d383626a5a162e93e), [d79316ff8](https://github.com/ymusic-io/YMusicV4/commit/d79316ff84e0f2e6bd3b6df2e405bb4899a766bc), [9b94d61ff](https://github.com/ymusic-io/YMusicV4/commit/9b94d61ffb3406479a107efb971bcc5c972f50a5), [43d29aa03](https://github.com/ymusic-io/YMusicV4/commit/43d29aa0328da377310fcbd4b3dfcfe6c8632467))
- Switch a YouTube video's audio track, e.g. take an auto-dubbed video back to its original language ([#36](https://github.com/ymusic-io/YMusicV4/issues/36), [8d5e10ae6](https://github.com/ymusic-io/YMusicV4/commit/8d5e10ae6128f98236840c2b05911e9c9b8b4a6c), [d04f2e1f7](https://github.com/ymusic-io/YMusicV4/commit/d04f2e1f775a37cf5933f3169fb286cbcfd525a7))
- Android: Open with YMusic for audio and video files from file managers and chat apps ([67f0a273e](https://github.com/ymusic-io/YMusicV4/commit/67f0a273eaa343cb717c0e45c548ee7a0a848f05))
- Now Playing: Search lyrics menu item ([35b76fa58](https://github.com/ymusic-io/YMusicV4/commit/35b76fa589992076af91d2dfa765b94b617f73c2))
- Now Playing: the playing song is highlighted in the queue, and a button next to "Up next" scrolls back to it ([#40](https://github.com/ymusic-io/YMusicV4/issues/40), [3a3933bf4](https://github.com/ymusic-io/YMusicV4/commit/3a3933bf4198e97a3885332b9674b6b730fb9b74))
- Library: sort songs by track number ([#46](https://github.com/ymusic-io/YMusicV4/issues/46), [fb519acc8](https://github.com/ymusic-io/YMusicV4/commit/fb519acc8feaaaa5bb033186f7aaa8e306ad561a))
- Subscribe / Unsubscribe from a channel row's menu when signed in ([bb47fdbc3](https://github.com/ymusic-io/YMusicV4/commit/bb47fdbc3996ef21f7f632b34396239c75cca4ad))

### FIXED
- Android: playback crashed on some phones, mostly on Android 15 and custom ROMs, while setting the cover art for the media notification ([#75](https://github.com/ymusic-io/YMusicV4/issues/75), [#49](https://github.com/ymusic-io/YMusicV4/issues/49), [135b8830c](https://github.com/ymusic-io/YMusicV4/commit/135b8830cddbabb67ea11484a700ab7b9125823c))
- macOS 11: the app crashed at launch ([#54](https://github.com/ymusic-io/YMusicV4/issues/54), [8f5c41c32](https://github.com/ymusic-io/YMusicV4/commit/8f5c41c32ce1fdd195ae0d3b84a86d14e672d308))
- iOS: YouTube sign-in never finished when the YouTube app was installed ([594e70005](https://github.com/ymusic-io/YMusicV4/commit/594e7000569ac4bcf4adfde93d5f6f1d016b882f))
- Android: 1080p and higher videos dropped frames, froze while the audio played on, and heated up phones without a hardware AV1 decoder ([#52](https://github.com/ymusic-io/YMusicV4/issues/52), [e1a2ffdc1](https://github.com/ymusic-io/YMusicV4/commit/e1a2ffdc1964de2bfb803237f0262b04b74dbc12))
- Opus and Ogg files (.opus, .ogg, .oga) couldn't be added to the Library ([#23](https://github.com/ymusic-io/YMusicV4/issues/23), [51ea29a82](https://github.com/ymusic-io/YMusicV4/commit/51ea29a82afb8617f1104cb9bda2729735b373bd))
- Playlist download: the mini player covered the Download button ([#65](https://github.com/ymusic-io/YMusicV4/issues/65), [#68](https://github.com/ymusic-io/YMusicV4/issues/68), [#41](https://github.com/ymusic-io/YMusicV4/issues/41), [4c97b0745](https://github.com/ymusic-io/YMusicV4/commit/4c97b07458a560e23f0c0c413cb4e22067bf9fcd))
- A download folder that already held music looked empty until a rescan ([965394ae3](https://github.com/ymusic-io/YMusicV4/commit/965394ae39a8e7818d52220d31699d61a0a18e20), [b88f99000](https://github.com/ymusic-io/YMusicV4/commit/b88f990002a17c80c3932467821c8dbc76284a24))
- Some YouTube thumbnails and comment avatars didn't load ([338fbffa2](https://github.com/ymusic-io/YMusicV4/commit/338fbffa21bf60a212a0d01e7f1edbdd3b46a92e))
- Desktop: clicking the app icon didn't bring back a window hidden to the tray ([0ef358ce4](https://github.com/ymusic-io/YMusicV4/commit/0ef358ce4762f6550157baa8f12a56be17b60792))

### IMPROVED
- Speed and pitch move in 0.01 steps, and holding −/+ keeps stepping ([#67](https://github.com/ymusic-io/YMusicV4/issues/67), [9b72f1e70](https://github.com/ymusic-io/YMusicV4/commit/9b72f1e70bb7cb058d2f997b2135215afe1a0257))
- Ukrainian: strings that still showed in English are translated ([#56](https://github.com/ymusic-io/YMusicV4/issues/56), [ff2f101ef](https://github.com/ymusic-io/YMusicV4/commit/ff2f101ef92d57afadea381c19d8600efcbb06d4))

## 4.0.11

### NEW
- Arrange your own playlist's songs: a Manual sort opens a dialog to drag, remove, and stack songs at the top with Move to top ([#28](https://github.com/ymusic-io/YMusicV4/issues/28), [36fedbc2e](https://github.com/ymusic-io/YMusicV4/commit/36fedbc2e112c468588dfa0abf2b2a7718ed1605))
- Swipe a song in the Now Playing queue to the right to remove it ([#28](https://github.com/ymusic-io/YMusicV4/issues/28), [ab2ca58ed](https://github.com/ymusic-io/YMusicV4/commit/ab2ca58ed0b92e87f2755ef7dbb0daa390cdc515))
- "YMusic updated" notice on the first launch after an update, with a link to the changelog ([c6fe6aeea](https://github.com/ymusic-io/YMusicV4/commit/c6fe6aeea7a4c8cde71651786e6b5446be4cc611))
- Feedback: reach us on GitHub or by email quoting your anonymous Device ID, which finds the app logs sent with it, or send a message straight from the app ([0793283a4](https://github.com/ymusic-io/YMusicV4/commit/0793283a44f137d4583f26d43e345b03bb1d0ef3))

### FIXED
- "Load more" in your own YouTube playlist stopped after the first 100 videos ([#17](https://github.com/ymusic-io/YMusicV4/issues/17), [9a123a519](https://github.com/ymusic-io/YMusicV4/commit/9a123a519b6800960572e8b347288daba3f91622))
- Sort chips on your YouTube library pages (e.g. Playlists) failed ([ad07107ee](https://github.com/ymusic-io/YMusicV4/commit/ad07107eecbb82262ad67741f829e70bd9b3fccb))
- Android: every download failed with an error code when the download folder couldn't be reached; it now asks to choose the folder again ([f163a0ba3](https://github.com/ymusic-io/YMusicV4/commit/f163a0ba3af21eb33ba7bb37e173fe412269081d))
- Android 10: downloads failed into a folder holding more than about 1000 files ([1de8fca59](https://github.com/ymusic-io/YMusicV4/commit/1de8fca59f8fb4127794834b56590eec2045b192))
- Android: installing a downloaded update could fail because the system cleared the APK ([36c5f8bf0](https://github.com/ymusic-io/YMusicV4/commit/36c5f8bf0e902857bf605e4f923502f24d659c47))
- Library folders that were deleted, became unreadable or lost their access kept showing their songs ([3f420c5de](https://github.com/ymusic-io/YMusicV4/commit/3f420c5deafad4318251f8c017c5fb6db13bbdd5), [4ff5a730a](https://github.com/ymusic-io/YMusicV4/commit/4ff5a730a2a1b89d7a62b68d18c63fcd9b8d841e))
- Android: .m3u/.m3u8 playlist files showed up as songs and crashed when played ([25f654ec1](https://github.com/ymusic-io/YMusicV4/commit/25f654ec105c500c81e5f9064114ed81684b3c27))
- Downloading some videos that YouTube was still processing failed with an error code ([4a0fe952f](https://github.com/ymusic-io/YMusicV4/commit/4a0fe952f9bb13f427e9cb25f845b40dd685332f))
- Android: crash entering picture-in-picture on some phones ([a91f2951b](https://github.com/ymusic-io/YMusicV4/commit/a91f2951bc5b903bb0f418668552d3850bad0b2a))
- Android: the app could freeze on some phones while updating the playback notification ([6ec2ca6ef](https://github.com/ymusic-io/YMusicV4/commit/6ec2ca6ef15c49a07a60ce1574148299f17fe9c6))
- With shuffle on, a song added with Play next got shuffled away; queued songs now play next, in the order added ([#24](https://github.com/ymusic-io/YMusicV4/issues/24), [4331d57ae](https://github.com/ymusic-io/YMusicV4/commit/4331d57aede4cb148e3a8d243447fca055ec7ee4))
- Android: swiping from the left edge opened the menu only on Discover's Home tab; elsewhere it also went back ([834a9560b](https://github.com/ymusic-io/YMusicV4/commit/834a9560be660fb11fc6613fd84ee207097ff443))
- Right-to-left languages: the tab underline sat under the wrong tab, and dragging the seek bar moved it the opposite way ([#26](https://github.com/ymusic-io/YMusicV4/issues/26), [9389db323](https://github.com/ymusic-io/YMusicV4/commit/9389db323789636fb63c394e5ac4fb1728a66535), [80872090b](https://github.com/ymusic-io/YMusicV4/commit/80872090b3d7e2d035d0ba66ebb57b8ef9226cc5))

### IMPROVED
- A video that can't play shows YouTube's own reason, in your language (e.g. "Video unavailable · This video is private") ([ee007fb5e..9bf684382](https://github.com/ymusic-io/YMusicV4/compare/6ec2ca6ef15c49a07a60ce1574148299f17fe9c6...9bf6843823d9200c4e7d4b8b9237e93d2847e50a))
- A song from a Library folder the app lost access to says so and offers to add the folder again ([3b0cbe825..63b6518a5](https://github.com/ymusic-io/YMusicV4/compare/1ccdd1f0b066130a50a9fe13751fdab7411b94d7...63b6518a508fde89dd17219892ac758aad51b6d6))
- Choosing a download folder checks that files can be saved there, and says why not ([d3c13f487..a42bf0cb6](https://github.com/ymusic-io/YMusicV4/compare/c065b2c42bebb02257e6d3f529f3d2623ce3d947...a42bf0cb69947863fa365146382d9af80032378d))
- Discover tabs sit next to the title on any window at least 650dp wide, and fill the row evenly on phones ([#12](https://github.com/ymusic-io/YMusicV4/issues/12), [cd86791f8](https://github.com/ymusic-io/YMusicV4/commit/cd86791f8fe07da13644a77e2e9a0baa9fe9b6ac), [d61271f42](https://github.com/ymusic-io/YMusicV4/commit/d61271f428e3623a0c3bfa5017117766bde93033))
- Larger account menu rows in Discover's Library ([1de85f565](https://github.com/ymusic-io/YMusicV4/commit/1de85f565b769987f0a0355649abca086732b96a))
- A YouTube video blocked by the bot check says "Botguard bypass failure" instead of a bare error code ([58196378f](https://github.com/ymusic-io/YMusicV4/commit/58196378fc7c4d2410f16c419799106d3cfa946c))

## 4.0.10

### NEW
- Library: "Shuffle all" on the Songs tab, and a shuffle button in playlist, artist and album lists ([7946634bd](https://github.com/ymusic-io/YMusicV4/commit/7946634bd21633d8e511e47b601b710ec87f9bdb))
- Repeat and shuffle are remembered across app restarts ([bf6980155](https://github.com/ymusic-io/YMusicV4/commit/bf6980155aaad848154d93c296f8ba0e8e7000b2))
- Landscape Now Playing: the queue follows the playing song ([b3733dbea](https://github.com/ymusic-io/YMusicV4/commit/b3733dbea8824f77fdc391ed0c6956ddd36dcf0d))

### FIXED
- "Load more" in a YouTube playlist added random songs and other playlists instead of the playlist's own songs ([#17](https://github.com/ymusic-io/YMusicV4/issues/17), [c3b16c0c4](https://github.com/ymusic-io/YMusicV4/commit/c3b16c0c48b27956a7b56f365749618435a91e48))
- A YouTube playlist's All / Videos / Shorts chips and your own playlist's sort order did nothing ([90a46674a](https://github.com/ymusic-io/YMusicV4/commit/90a46674a2d6c710814c18f26fa60a81d2248fb4))
- Tapping a song in a playlist turned shuffle off ([bf6980155](https://github.com/ymusic-io/YMusicV4/commit/bf6980155aaad848154d93c296f8ba0e8e7000b2))

### IMPROVED
- Bigger, easier-to-tap icon buttons, chips, tabs, search field and "View all" links ([#12](https://github.com/ymusic-io/YMusicV4/issues/12), [b7122c4d1..0d021e35a](https://github.com/ymusic-io/YMusicV4/compare/42688bb23d1b620acde6fdf1f7787ffd4ebe1279...0d021e35afdeeddc6956e5e951e847925e57ce31))
- Video controls grow on large screens, and the portrait video controls get ±10s buttons ([#12](https://github.com/ymusic-io/YMusicV4/issues/12), [320894247](https://github.com/ymusic-io/YMusicV4/commit/320894247955b0216008f8aa535780ed86dc7afb), [0af7188f4](https://github.com/ymusic-io/YMusicV4/commit/0af7188f4e269c203c206991b7cbaf91bea2cbb9))
- Landscape Now Playing: a wider queue with larger rows on large screens ([#12](https://github.com/ymusic-io/YMusicV4/issues/12), [b3733dbea](https://github.com/ymusic-io/YMusicV4/commit/b3733dbea8824f77fdc391ed0c6956ddd36dcf0d))
- More phones get the wide-thumbnail feed list ([42688bb23](https://github.com/ymusic-io/YMusicV4/commit/42688bb23d1b620acde6fdf1f7787ffd4ebe1279))

## 4.0.9

### NEW
- Import your playlists from other music apps (Android), and back up and restore your playlists ([a699bd263..f682e498e](https://github.com/ymusic-io/YMusicV4/compare/0a2baec60c65482323e4b1317079dfa8d9843768...f682e498e6ffd122a9be63eb0e15fb1403fffe15))
- Android: play by voice with Gemini or Google Assistant ("play Eminem on YMusic"), on the phone and in Android Auto ([8ceba3de9](https://github.com/ymusic-io/YMusicV4/commit/8ceba3de90106ab234992c9213309cab19965f6c))
- Android Auto: your YouTube library (history, Watch later, Liked videos, subscriptions) in Discover ([5bf1149ca](https://github.com/ymusic-io/YMusicV4/commit/5bf1149ca10120f896d5888a8ed0c6f88aceb8d1))
- Android Auto: shuffle and repeat buttons ([ad5bb9895](https://github.com/ymusic-io/YMusicV4/commit/ad5bb98955c03fd062be107b0aeff19e1ff6c5f5))
- Create a local playlist from the downloaded videos of a YouTube playlist ([332363518](https://github.com/ymusic-io/YMusicV4/commit/332363518c1be8c49b6b2209f203d7c809f3c03d))
- Android: rotation lock button in fullscreen video ([0901988ec](https://github.com/ymusic-io/YMusicV4/commit/0901988ec64d0e6234a4d7b7fc4c69f690681d86))
- "Index prefix" option when downloading a playlist numbers the files in playlist order ([dda3963fc](https://github.com/ymusic-io/YMusicV4/commit/dda3963fc1bf199082569176c1cdcf10cf72562c))
- Terms of use and privacy policy, linked from Settings → About ([3b9e735b4..1d1f827f3](https://github.com/ymusic-io/YMusicV4/compare/947e33bf52aab79421c04e00701cabeb5006fbc0...1d1f827f37e150708a2a5132772bbf53d1454ca5))

### FIXED
- Android Auto: YMusic kept loading and showed nothing when the app wasn't already running ([b82f5ac77](https://github.com/ymusic-io/YMusicV4/commit/b82f5ac77bbdd30cffae6fff86ea537bf02c2e79))
- Android Auto: YT Music albums and playlists didn't open ([162f9498c](https://github.com/ymusic-io/YMusicV4/commit/162f9498c5486815cdb1fa73c2cdf4470668c276))
- With repeat one on, a video froze on its last frame after seeking to the end or skipping a SponsorBlock outro ([a26ae1633..f70bd983a](https://github.com/ymusic-io/YMusicV4/compare/119c329cfde5dca30c6e5ad87fad1b696085452e...f70bd983ad7b19776c5d366b2f0d7fa5f64e0dc8))
- Playback could stay loading forever after a long listening session ([b0901d97c](https://github.com/ymusic-io/YMusicV4/commit/b0901d97ce8854090dc901a09ad1de6d775514db))
- Search suggestions with accented letters (e.g. Vietnamese) came out garbled ([119c329cf](https://github.com/ymusic-io/YMusicV4/commit/119c329cfde5dca30c6e5ad87fad1b696085452e))
- Right-to-left languages: the rewind and fast-forward buttons were swapped ([ce63e0b6c](https://github.com/ymusic-io/YMusicV4/commit/ce63e0b6ca4e873649b3325cca0c89ea105c03f5))
- Library sort order reset to Name on every launch ([88505834a](https://github.com/ymusic-io/YMusicV4/commit/88505834a2cd320ae206f1336278c8f89751a0be))

### IMPROVED
- Sharper YT Music artwork in the feed, Now Playing, notification, widgets and Android Auto ([d13756a23..d708d47f1](https://github.com/ymusic-io/YMusicV4/compare/b143b0e8711a5b8babc77b59d40b35d429fae043...d708d47f1984c53091c11e892e74924ab0f5f18b))
- Android Auto: menus follow the app's language, and a failed song shows a readable error ([8abe33f9f..b143b0e87](https://github.com/ymusic-io/YMusicV4/compare/dda3963fc1bf199082569176c1cdcf10cf72562c...b143b0e8711a5b8babc77b59d40b35d429fae043))
- A playback failure from a lost connection says "Can't reach YouTube" instead of an error code ([abbe3f4e0](https://github.com/ymusic-io/YMusicV4/commit/abbe3f4e0d737c3ca04b8354d58110d22ca60dad))
- A download whose connection dropped says "Network error" instead of "Something went wrong" ([982817918](https://github.com/ymusic-io/YMusicV4/commit/9828179186d76d6f49fe971e106107c82efdb59b))
- A YouTube video that can't start gives up after 20 s with an error naming the step that stalled, instead of failing too early or hanging ([2428c5aa4..151dcf4fd](https://github.com/ymusic-io/YMusicV4/compare/abbe3f4e0d737c3ca04b8354d58110d22ca60dad...151dcf4fd25a676c8220adef2775414d90b65dcc), [aa6a08cf7](https://github.com/ymusic-io/YMusicV4/commit/aa6a08cf757f1940869ccaa953ea5d0b451822e7))
- Android: the update dialog offers downloading in the browser right away when a download can't start without the install permission, and for an install only after the permission has been pending for an hour ([507e9d603](https://github.com/ymusic-io/YMusicV4/commit/507e9d603d587b855eb9397cf40abac024661025), [0db8e98e6](https://github.com/ymusic-io/YMusicV4/commit/0db8e98e693c974fb1b7d2cc0116bdaa3f6a9965))

## 4.0.8

### FIXED
- Android: a few seconds of fast-forwarded audio during long video playback ([83e79c9d5](https://github.com/ymusic-io/YMusicV4/commit/83e79c9d5e5b18accddd320bb7eaf60dd69db289))
- macOS: long video sessions could turn choppy, with gaps of silence ([26fba8326](https://github.com/ymusic-io/YMusicV4/commit/26fba8326319d6b5d362038fe0394cda83a7285a))
- Crash when tapping a link on a device with no web browser ([50d656afa](https://github.com/ymusic-io/YMusicV4/commit/50d656afa65097ecd4b891ed9b9cf62d550c9e36))
- A rare crash while the app writes its logs ([268b7c23d](https://github.com/ymusic-io/YMusicV4/commit/268b7c23debff19d13462524e6005be3b31a8192))
- A download finished just before a power loss could be left as an empty file ([bf3039893](https://github.com/ymusic-io/YMusicV4/commit/bf303989389e995239dbeec54be1a79f620883f4))
- Cached video could play corrupted after a power loss ([5a2daaf94](https://github.com/ymusic-io/YMusicV4/commit/5a2daaf94c0efaf8b46f92c1f439475b267efaf2))

### IMPROVED
- Fewer YouTube playback failures ("po token rejected") ([8f618579b..bfcda13c1](https://github.com/ymusic-io/YMusicV4/compare/344d8996f5879c8837fea4432d2bc28681eef3f5...bfcda13c1df0f79b2e5202545c1decca38f545f8))
- Playback fixes published between app updates reach the app right after launch ([fb2d01464](https://github.com/ymusic-io/YMusicV4/commit/fb2d0146487e096a75d8ec2902400d4010c4b3b0))

## 4.0.6

### IMPROVED
- Tapping Install now in the update dialog shows an installing progress bar until the app restarts ([852fd740d..d7e09a867](https://github.com/ymusic-io/YMusicV4/compare/84c97c82f...d7e09a867))
- Playback errors show on the video, also in fullscreen ([117519b4c](https://github.com/ymusic-io/YMusicV4/commit/117519b4c3b9fe6759e54cf7b1d7564e0439aa44))
- Less CPU use while YouTube video plays ([e4195937c](https://github.com/ymusic-io/YMusicV4/commit/e4195937c5062e78f5e46548baec11187c88ba5e))

### FIXED
- Android: a video quality the device can't play says so, and Retry plays it as audio only ([77ddfcbd3..117519b4c](https://github.com/ymusic-io/YMusicV4/compare/321ff5092375dc77782511e1cdbc6434007b797e...117519b4c3b9fe6759e54cf7b1d7564e0439aa44))
- Android: crash on some phones that don't support picture-in-picture ([03fb185f3](https://github.com/ymusic-io/YMusicV4/commit/03fb185f3c4abd32a0469951934e929cf3a56d5a))
- Quick and playlist downloads of a video with no Opus audio save M4A instead of failing ([38965304c](https://github.com/ymusic-io/YMusicV4/commit/38965304c2ebf298f4fa3a36ca6c4b29fc9f828c))
- Downloading a live stream that just ended says it is still processing instead of failing with an error ([8d2d22b6f..3e64ac498](https://github.com/ymusic-io/YMusicV4/compare/38965304c2ebf298f4fa3a36ca6c4b29fc9f828c...3e64ac498102750d51057b31d1102a30b066d9b1))
- Android: choosing a folder no longer crashes when the system Files app is disabled or missing; it says how to fix it ([54a5ea36d](https://github.com/ymusic-io/YMusicV4/commit/54a5ea36dc91a49bb026b326b886cdfd8089e3c4))
- Android: the app could freeze when the next video started loading ([886e02739](https://github.com/ymusic-io/YMusicV4/commit/886e02739cc82f986a90ecea474f0c4e8b29020d))
- Starting, resuming or pausing a download with full storage shows the disk-full error instead of silently doing nothing ([6e65f9253](https://github.com/ymusic-io/YMusicV4/commit/6e65f925347e43b4733259d5f4ce978c9860e482))
- Downloads of videos with long or emoji-heavy titles no longer fail to save ([35550ca51](https://github.com/ymusic-io/YMusicV4/commit/35550ca51d7ebc505dfbc75351d6b45ea1893c1a))
- Crash when tapping back or close twice quickly ([7a8c7b8b5](https://github.com/ymusic-io/YMusicV4/commit/7a8c7b8b543dc745358294d160bcf24250b76288))
- Android: playback notification and media controls could go missing after the player service restarted ([9d579be97](https://github.com/ymusic-io/YMusicV4/commit/9d579be97f6fb21c3e337b9839380c255f3882db))
- Downloads could fail partway when YouTube moved them to another server ([1b754af64](https://github.com/ymusic-io/YMusicV4/commit/1b754af6487341c82df226d1493bdd83794998cd))
- Android: crash on some Android 13 phones when pausing or swiping the app away ([a78022498](https://github.com/ymusic-io/YMusicV4/commit/a78022498550354537b8e5e03b5b1b0212b44e9f))
- A download that fails on a bad connection says "Network error" instead of "Something went wrong (E60034)" ([e46ac38ac](https://github.com/ymusic-io/YMusicV4/commit/e46ac38ace5643272beec49204a855453673d4ea))
- Android: removing a song whose file or folder was already deleted no longer shows an error ([fb150bcdd](https://github.com/ymusic-io/YMusicV4/commit/fb150bcdd6138f3b35214e538bd6ec702be12bd6))
- The Recommended tab no longer gets stuck on an error after the phone restarts unexpectedly ([cb0db20d6](https://github.com/ymusic-io/YMusicV4/commit/cb0db20d68fa0f97c5c17c7acd5da98f4fff4dcf))
- When the YouTube sign-in has expired, the app asks you to sign in again instead of failing everything with an error ([6fad90cc5](https://github.com/ymusic-io/YMusicV4/commit/6fad90cc50d76d92a39b87fedc13b26b9f8c4fd6))
- A supervised (Family Link) Google account gets a message explaining it can't be used ([6fad90cc5..89e92af16](https://github.com/ymusic-io/YMusicV4/compare/6fad90cc50d76d92a39b87fedc13b26b9f8c4fd6~1...89e92af164814abfdbb1546fa03a7f2498dcc1e4))
- Android: the app could freeze while downloads were updating their notifications ([9211de43b](https://github.com/ymusic-io/YMusicV4/commit/9211de43bcc3c6703962f8f7bea5bcc94d7cd9db))

## 4.0.5

### IMPROVED
- Android: faster song changes, and the last moment of a song is no longer cut off ([7d70b860f..95dfd84fd](https://github.com/ymusic-io/YMusicV4/compare/20de0ace6f1d5058529649112a233169055ae15e...95dfd84fdc3bee2bf46ca64d09142877c24407d0))
- Fewer YouTube playback failures ("An error occurred") ([1a01634d2..e6909faf8](https://github.com/ymusic-io/YMusicV4/compare/16b2cf4d0d2a0da4e7c6e10595ea928db8f38549...e6909faf81cfe01b0624b84ce62f66d935ebe7e1))
- Full storage shows "Not enough storage space" instead of an error code ([b3e8b2027..16b2cf4d0](https://github.com/ymusic-io/YMusicV4/compare/bf5fc3943619bca8cf3fd8cc364890d40f785901...16b2cf4d0d2a0da4e7c6e10595ea928db8f38549))
- Android: a download folder that was deleted asks you to pick the folder again ([bf5fc3943](https://github.com/ymusic-io/YMusicV4/commit/bf5fc3943619bca8cf3fd8cc364890d40f785901))
- Messages show above the mini player instead of covering it ([d71c20180](https://github.com/ymusic-io/YMusicV4/commit/d71c20180df4188490e7df05bbf85f105200cc5a))
- Home feed list: the line under the title wraps to two lines ([ada11f166](https://github.com/ymusic-io/YMusicV4/commit/ada11f1666dc91a36c627d8d92b29ae344d38612))

### FIXED
- Short audio gap after about 10 minutes of YouTube playback ([a64d84ba0](https://github.com/ymusic-io/YMusicV4/commit/a64d84ba0a8ee7935f08fa06e40fb99ba7867e18))
- Pause or restart after seeking back in long YouTube videos ([a7298582c..c640b7e9a](https://github.com/ymusic-io/YMusicV4/compare/e6909faf81cfe01b0624b84ce62f66d935ebe7e1...c640b7e9af507dce5ff53de3db8c6ffadcaadc7a))
- Some YouTube videos playing too fast, out of sync with the audio ([ff5197020](https://github.com/ymusic-io/YMusicV4/commit/ff5197020b953c93058b5d1622a9b85cc17c6eb7))
- Audio freezing at the change to the next song ([20de0ace6](https://github.com/ymusic-io/YMusicV4/commit/20de0ace6f1d5058529649112a233169055ae15e))
- Downloads failing after a playback error ([9278e7191](https://github.com/ymusic-io/YMusicV4/commit/9278e71910a6fea92039eef7d443257fdfee8512))
- Android: YouTube video freezing and jumping, mostly on live streams ([d122eaa22](https://github.com/ymusic-io/YMusicV4/commit/d122eaa221fe5a831aba5f90625bbf92ff329860))
- Android: black YouTube video until restart after turning the screen back on ([fe6158127..dfa555c3d](https://github.com/ymusic-io/YMusicV4/compare/504e253c342672fdb0da418e88c0d4169fe48778...dfa555c3d7209fa8fde4cb29d206ecfd8c89e9de))
- Android: YouTube playback stuck after a few seconds on some devices ([a8ce568b7](https://github.com/ymusic-io/YMusicV4/commit/a8ce568b796776903c33c38a620c03e02b3a1022))
- Android: crash when the system restarts the app in the background ([504e253c3](https://github.com/ymusic-io/YMusicV4/commit/504e253c342672fdb0da418e88c0d4169fe48778))

## 4.0.4

### NEW
- Android: equalizer apps and system sound effects work on YouTube playback, with a "Use system sound effects" switch in the Equalizer ([9c6f8a4fb..df765d62b](https://github.com/ymusic-io/YMusicV4/compare/fff79b8a504965f7d0080ca9483883fa3f87b318...df765d62b91054c5828ccd581239f70c68bf2a57))
- Android: Audio focus setting (pause when other apps play, or for calls only); unplugging headphones pauses YouTube too ([86c471ff4](https://github.com/ymusic-io/YMusicV4/commit/86c471ff4f8ef18ce20df0bbecb9380521baebcf))
- Android: runs on 32-bit ARM and x86 devices ([2ea5eccb1..73a20ca37](https://github.com/ymusic-io/YMusicV4/compare/839115bfa34fb7bef79cf9a6a84ad5d6c6043545...73a20ca37d30cd7281a898da92a975bdbc85ec16))
- Black (AMOLED) theme, and an accent color picker with presets ([d31440369](https://github.com/ymusic-io/YMusicV4/commit/d314403691451ff81dcdc48e762fcc4dd904d6d7))

### IMPROVED
- YouTube audio and video stay in sync after seeks, speed changes and resumes ([2643636b9..abef10006](https://github.com/ymusic-io/YMusicV4/compare/0a3e3014ba6d579aef61e9de400ae0058acf6653...abef100069c482f77d722aef3b54003674e1f5e6))
- YouTube audio plays at the best quality available ([a05021e53](https://github.com/ymusic-io/YMusicV4/commit/a05021e53c5f362c8b054a8925c9c369d4dc230a))
- Android: changing video quality no longer stalls or downloads the video again ([530a1d1d0](https://github.com/ymusic-io/YMusicV4/commit/530a1d1d011c276a3d3651177563e826de44f902))
- Downloads: only MP3 is re-encoded; M4A, WebM and video keep the original streams, and videos with Dolby audio download ([c5f29e06c..0a3e3014b](https://github.com/ymusic-io/YMusicV4/compare/38984c08b91079cc741e481b5dd32e14a19e15b1...0a3e3014ba6d579aef61e9de400ae0058acf6653))
- Speed and pitch sliders apply when you let go ([921e16461](https://github.com/ymusic-io/YMusicV4/commit/921e16461b153beb5f235467b67315fbbd017c07))
- Now Playing ⋮ menu grouped with icons and current values ([f6df44187](https://github.com/ymusic-io/YMusicV4/commit/f6df441875c39bbbb59cc3026963f5a622606b5a))
- Home feed: wide list on more phones, with up to three title lines ([0c3fa7e26..af1181f6b](https://github.com/ymusic-io/YMusicV4/compare/3136aa1f1c8017e78938146434ff86fc1d908d91...af1181f6bffcecf631de0cef189ada2423a3b8a1))
- Faster recovery when YouTube is slow to respond ([4bbf9b189](https://github.com/ymusic-io/YMusicV4/commit/4bbf9b189de64ae39ade60127b3c5381045c0586))
- Settings → About lists the app versions first ([04270e012](https://github.com/ymusic-io/YMusicV4/commit/04270e012e7cfcfa16c8b9435773f08d0bf2fcb9))

### FIXED
- Playlist skipping every second song ([9b2e0d3c9](https://github.com/ymusic-io/YMusicV4/commit/9b2e0d3c991e8b723b828348c6b8d6a2b58c90fc))
- Repeat one not looping YouTube videos ([839115bfa](https://github.com/ymusic-io/YMusicV4/commit/839115bfa34fb7bef79cf9a6a84ad5d6c6043545))
- Search suggestions not showing ([85e7d7a96](https://github.com/ymusic-io/YMusicV4/commit/85e7d7a9695b2300438b18c4347c2e67b03588b3))
- Downloaded subtitles that showed nothing ([cca979d54](https://github.com/ymusic-io/YMusicV4/commit/cca979d54b7d19c923d15aa90ec18bbe8ada7326))
- Volume staying low after another app lowered it ([7905af815](https://github.com/ymusic-io/YMusicV4/commit/7905af815caa9fe0413cb65e0fac076bcd63d814))
- Seeking near the end of a YouTube track skipped to the next one ([cdb430450](https://github.com/ymusic-io/YMusicV4/commit/cdb4304506439786977e1c268eb3f146f2e4acae))
- iOS, macOS: audio skipped when resuming after a pause ([ddb55f7d1](https://github.com/ymusic-io/YMusicV4/commit/ddb55f7d1fe35ad593284c524e598847f85d89c2))
- Speed & pitch dialog showing "Volume 100%%" ([3136aa1f1](https://github.com/ymusic-io/YMusicV4/commit/3136aa1f1c8017e78938146434ff86fc1d908d91))
- Desktop: occasional crash at startup on slow machines ([a38bc9bad](https://github.com/ymusic-io/YMusicV4/commit/a38bc9badb084f01bab7f17df6b7ead0d03d2283))

## 4.0.3

### NEW
- Subscriptions feed ([d17aa149a..21e499d22](https://github.com/ymusic-io/YMusicV4/compare/d22d94948075c2a4ec63197e06d5f66ebe2209e9...21e499d228971b9791b59193a5f33747d8ecd025))
- MP3 downloads at 320 kbps, with a "Download MP3 at original bitrate" setting ([9031408b3](https://github.com/ymusic-io/YMusicV4/commit/9031408b3609dec19771cdca628223050a28a1cc))
- Android: swipe from the screen edge opens the menu on Home ([cdf71f64f](https://github.com/ymusic-io/YMusicV4/commit/cdf71f64f1737539dadd21ca8bf1a4b750e61f6b))
- Audio-only choice is remembered across restarts ([e129bb1d3..420a63495](https://github.com/ymusic-io/YMusicV4/compare/b854830cbe919c7e79bfcf98c5dc23847bd4e86f...420a6349543c562f4ac817fe2b93b77ec6cfb6dc))
- Feedback can be sent with crash reports turned off ([16cce57a0..b0eeafca0](https://github.com/ymusic-io/YMusicV4/compare/7f7e388773c07b43e85b2c4239481ea104086b5c...b0eeafca0e41a18da26d622bec86120564091f1c))
- macOS 26 app icon ([9936de0c1](https://github.com/ymusic-io/YMusicV4/commit/9936de0c17a4548827bd4558c2599944b3fc56a9))

### IMPROVED
- WebM is offered only when the video has a WebM stream ([91bac9839..81986c682](https://github.com/ymusic-io/YMusicV4/compare/9031408b3609dec19771cdca628223050a28a1cc...81986c682989ced1ee830fc884f19deffe8bbd98))
- Download notification shows the same error as the Downloads screen ([b854830cb](https://github.com/ymusic-io/YMusicV4/commit/b854830cbe919c7e79bfcf98c5dc23847bd4e86f))
- Screen reader support in Settings and Now Playing ([02d5d8153..d2081a2a0](https://github.com/ymusic-io/YMusicV4/compare/a9908e2810b9d86e2713fc14143d7df0b47c6175...d2081a2a03d76a2dac6ac306107397806fd7ca46))
- A local file deleted from storage shows "File missing"; YouTube's "An error occurred" can be retried ([55533bf82..392af1a18](https://github.com/ymusic-io/YMusicV4/compare/420a6349543c562f4ac817fe2b93b77ec6cfb6dc...392af1a188b5fcbc419b4dbb6b5e4d2536fac914))

### FIXED
- Crash on Android 7 ([45630246c](https://github.com/ymusic-io/YMusicV4/commit/45630246c75a2b8d622e4177ab177d3701a64bef))
- ANR while Now Playing shows the FPS badge ([5d2d4b08e](https://github.com/ymusic-io/YMusicV4/commit/5d2d4b08e19a78c089353b669ddda5d4e3ff4b6b))
- Android: video stalls then jumps on low-end devices ([d8e4e46ae](https://github.com/ymusic-io/YMusicV4/commit/d8e4e46aeb223d862d33f9e73b018a1eec1e1a01))
- Trending charts failing to load ([2b2691963..7f7e38877](https://github.com/ymusic-io/YMusicV4/compare/55a865f719eae55318ba5d3c2024a378f244cdae...7f7e388773c07b43e85b2c4239481ea104086b5c))
- Changing the language reset the region ([43d297fff..55a865f71](https://github.com/ymusic-io/YMusicV4/compare/21e499d228971b9791b59193a5f33747d8ecd025...55a865f719eae55318ba5d3c2024a378f244cdae))
- Downloads marked failed after the file was saved ([8828e282d](https://github.com/ymusic-io/YMusicV4/commit/8828e282db0ddd9403cf9aae5367e133832ec18a))
- Downloads to a read-only folder ([90fc54ffe..5c6db8e4b](https://github.com/ymusic-io/YMusicV4/compare/8828e282db0ddd9403cf9aae5367e133832ec18a...5c6db8e4bca35d56111df7ad6edef5357785271d))
- App update: withdrawn or replaced releases kept showing, double install on desktop ([3fd27c8e4..8f156551e](https://github.com/ymusic-io/YMusicV4/compare/d2081a2a03d76a2dac6ac306107397806fd7ca46...8f156551ef64691a1c58bd9008883035ff000489))
- Desktop: QR code crash in the release build ([b1addabe5](https://github.com/ymusic-io/YMusicV4/commit/b1addabe52744e874f7dcc8779bd4f46f265c07e))
- iOS: app bar too low on search, playlist and channel pages ([6d5738bfb](https://github.com/ymusic-io/YMusicV4/commit/6d5738bfb4492f091fa0675eb123c2cd080e134f))
- iOS: large responses stopping mid-download ([c5de91f95](https://github.com/ymusic-io/YMusicV4/commit/c5de91f952532ed7484b3c909f1baab5b30c516e))
- Mini-player missing the artist name ([4d0a08b3e](https://github.com/ymusic-io/YMusicV4/commit/4d0a08b3eada2fc5c8d6543b7863bc2e0f558fdb))
- Shuffle enabled on an empty playlist ([b6a734851](https://github.com/ymusic-io/YMusicV4/commit/b6a734851ee055c51145fe0483c1d01a3537b55e))
- Captcha Verify losing its retry ([a9908e281](https://github.com/ymusic-io/YMusicV4/commit/a9908e2810b9d86e2713fc14143d7df0b47c6175))

### INTERNAL
- Telemetry: one issue per feedback subject; network failures no longer open issues; certificate errors are not "Network error"; JS bundle versions reported at launch ([5534a0f72](https://github.com/ymusic-io/YMusicV4/commit/5534a0f7228d44bd6579f19778f1258e141fcf37), [d17b9c916](https://github.com/ymusic-io/YMusicV4/commit/d17b9c916a1fcf536e427ea55c8c269b6606b8c7), [64cee5f29](https://github.com/ymusic-io/YMusicV4/commit/64cee5f293bdbc2d1af437c728e0a2ca990add56), [4e5796cf0](https://github.com/ymusic-io/YMusicV4/commit/4e5796cf001b17ffe6a4f9f3cea48a674435b967))
- BotGuard dump records more host inputs ([8a5400ca5..e1b06647f](https://github.com/ymusic-io/YMusicV4/compare/8f156551ef64691a1c58bd9008883035ff000489...e1b06647fa241090e7915b7eaf5a2b17bb2327e8))
- Android: native debug symbols uploaded to Sentry ([d8593b2b8..3e8142e47](https://github.com/ymusic-io/YMusicV4/compare/45630246c75a2b8d622e4177ab177d3701a64bef...3e8142e47fee0414df5c3a5d1beb042a179498b7))

## 4.0.2

### Updates
- Update dialog: one layout for every download state; progress and errors only after Download; official download site link ([6ff8bc841..f3e15cfcf](https://github.com/ymusic-io/YMusicV4/compare/a6ad0790873a745cd47f7adf5d2cc70a84296521...f3e15cfcf54655e56626ecfe3b24e6746d896f31))
- Android update: pin the install session to this app; ask for the install permission, or download in the browser ([5f1b1ab39..3e9a718b5](https://github.com/ymusic-io/YMusicV4/compare/8aaaa737910a5bb8784b5dec24396a8441da69f1...3e9a718b564ceef86e6b165896ccd186e6272b2b))

### Settings
- Versions row in Settings › About: app, running EngineJs and PlayerJs ([c70416647..70244ffec](https://github.com/ymusic-io/YMusicV4/compare/ca4955e3e82b6f39a313fc6f1a4b0f23f2fdc35f...70244ffec4dcb0445b5d4f10ae5dd423040b64de))
- Desktop nav menu shows the version and arch ([1e8282af2](https://github.com/ymusic-io/YMusicV4/commit/1e8282af2a11facb59f005b351a05e5dcaa12bd2))

### CI
- Sign update-config.json with the keys in secrets.enc; fail when a key is missing ([ca4955e3e](https://github.com/ymusic-io/YMusicV4/commit/ca4955e3e82b6f39a313fc6f1a4b0f23f2fdc35f), [b94150e33](https://github.com/ymusic-io/YMusicV4/commit/b94150e33285f6c4b94cf42c6207a6ecc1c9a300))

## 4.0.1

### Crashes / ANRs
- Fix ANR in media browser service `onGetRoot` deadlock ([2a769a633](https://github.com/ymusic-io/YMusicV4/commit/2a769a6335b51f459b12e28f65e3a9ecf008f43a))
- Fix OpenSL use-after-free on pause/flush ([c3ae35454..ce84c6702](https://github.com/ymusic-io/YMusicV4/compare/98cebef8064acb02c3dec0b446f6a6c3f23929b2...ce84c670225940b4594e6e4927f7e1272756d5bc))
- Fix PiP aspect ratio crash ([3b0c2a7ab](https://github.com/ymusic-io/YMusicV4/commit/3b0c2a7ab4ed0f50a6cf722bd3ad81d298052ca2))
- Fix foreground service start refusals ([ecdc9e20f..c84fccd88](https://github.com/ymusic-io/YMusicV4/compare/eed2ec45c181716ddad6d739a9b56ab079ec2fe0...c84fccd889bce66c31c657154dee562ee66a958f))
- Fix SAF delete of an already-missing file ([d2c1ea6b3..b7fee4322](https://github.com/ymusic-io/YMusicV4/compare/c84fccd889bce66c31c657154dee562ee66a958f...b7fee4322118717bcb50f10a59b116324c7104e7))
- Fix null SAF display name ([c44a122f9](https://github.com/ymusic-io/YMusicV4/commit/c44a122f93c740cb50158aa5580ed351122afb2e))
- Capture PlaybackCoordinator collector failures instead of crashing ([3b355f138](https://github.com/ymusic-io/YMusicV4/commit/3b355f1389fb698ee1c309710d1821bf3f61c293))

### Playback
- Fix duplicate PlaybackCoordinator: double failure reports and watch time ([b677dcdc5..8af53b329](https://github.com/ymusic-io/YMusicV4/compare/7130f48e52075a9552e7631c0f87c5ba96a39aa7...8af53b329038762d2f2a87e7492025a4e32cb657))
- Playability verdicts with Sign in for login/age-gated videos ([0139dbe47..537159152](https://github.com/ymusic-io/YMusicV4/compare/3b355f1389fb698ee1c309710d1821bf3f61c293...537159152de4df6303cc04d1947e328cbe47d34a), [5fa3feece](https://github.com/ymusic-io/YMusicV4/commit/5fa3feece2e2e7f4c7b6818b6f9e6813a6549e37))
- Captcha detection (429 / g-recaptcha) and Verify flow ([5ea5b3690..eed2ec45c](https://github.com/ymusic-io/YMusicV4/compare/68473bb9d943d6d57bc0a527b13c8c91c915fc28...eed2ec45c181716ddad6d739a9b56ab079ec2fe0))
- Prepare timeout back to 10 s, recover when engine becomes ready late ([c60ff337a..1ab33f7e8](https://github.com/ymusic-io/YMusicV4/compare/453b22a1999bd602669a70b2e56e8cf21214c73a...1ab33f7e8375dc53806bca62fa0d76a191b9a9be))
- Drop stale prewarm results ([7130f48e5](https://github.com/ymusic-io/YMusicV4/commit/7130f48e52075a9552e7631c0f87c5ba96a39aa7))
- Session cache bypass after storage failure ([70e770437..453b22a19](https://github.com/ymusic-io/YMusicV4/compare/45d91b62fa0f0503372a9a3ac942d1b79332933c...453b22a1999bd602669a70b2e56e8cf21214c73a))
- Fix StreamProtectionStatus handling: 1 ok, 2 pending, 3 invalid PO token ([2df6d3861..98cebef80](https://github.com/ymusic-io/YMusicV4/compare/6f0b43ee8544e8ab04d782f089fd8ebaafdbc419...98cebef8064acb02c3dec0b446f6a6c3f23929b2))

### Downloads
- Fail fast on playability refusal instead of player-response timeout ([b836f4af6..e1ac96106](https://github.com/ymusic-io/YMusicV4/compare/537159152de4df6303cc04d1947e328cbe47d34a...e1ac961065104e1532513fb84ffca7a2e03dff26))
- Keep SABR cursor in step with on-disk bytes ([a2c428353..6f0b43ee8](https://github.com/ymusic-io/YMusicV4/compare/5fa3feece2e2e7f4c7b6818b6f9e6813a6549e37...6f0b43ee8544e8ab04d782f089fd8ebaafdbc419))
- Treat missing player-cache files as cache misses ([bfa5b2531..45d91b62f](https://github.com/ymusic-io/YMusicV4/compare/2a769a6335b51f459b12e28f65e3a9ecf008f43a...45d91b62fa0f0503372a9a3ac942d1b79332933c))
- Move scratch files to noBackupFilesDir with orphan cleanup ([2c9b7f7e9..790e5dcd1](https://github.com/ymusic-io/YMusicV4/compare/3b0c2a7ab4ed0f50a6cf722bd3ad81d298052ca2...790e5dcd16849110b8654b13e93ee80616772dc6))
- Re-pick lost SAF folder and retry without re-downloading ([38c4666b4..35ebad576](https://github.com/ymusic-io/YMusicV4/compare/790e5dcd16849110b8654b13e93ee80616772dc6...35ebad57673ab9157a23b9d696792875ccd75371))
- Sign-in resolve dialog on Resume, Remove for unavailable videos ([47ec766ce..066db7ff0](https://github.com/ymusic-io/YMusicV4/compare/35ebad57673ab9157a23b9d696792875ccd75371...066db7ff03747829975c93a305d2b690740a43ad))
- Fix pause/resume double-run race ([c6bf07bf1](https://github.com/ymusic-io/YMusicV4/commit/c6bf07bf1af6e7943159002d7c51fc66d305d541))
- Fix duplicate file when pause lands after publish ([541d894e5](https://github.com/ymusic-io/YMusicV4/commit/541d894e55d826c5fae2c5b41a1188cf8d8fe997))
- Fix task stuck in DOWNLOADING when an end-state write fails ([d9fe968ae](https://github.com/ymusic-io/YMusicV4/commit/d9fe968ae9c073edf01517005983e7e30d1fe6e5))
- Classify socket timeouts / connection drops as network errors ([3e0bad3f7](https://github.com/ymusic-io/YMusicV4/commit/3e0bad3f7e6713e3cc6404e14796ea0108bb2687))

### Network / storage
- Mid-body read timeout no longer surfaces as a parse error ([84fa2af7b..17e9c0d99](https://github.com/ymusic-io/YMusicV4/compare/c44a122f93c740cb50158aa5580ed351122afb2e...17e9c0d99ae54badd43552ec941a4ff000344624))
- Per-status site codes for non-2xx responses ([ec85ce957..bb85e9929](https://github.com/ymusic-io/YMusicV4/compare/c1940080ee006d7bcbb1819309399de6fa5bc07a...bb85e9929701d109ffa7f6813989a0dcc8214d36))
- Upserts compatible with SQLite < 3.24 (Android ≤ 10) ([2d25bcb4e](https://github.com/ymusic-io/YMusicV4/commit/2d25bcb4e7306f0239801c8fe1b80f8fde94252d))
- DiskLruCache survives temp dir removal ([68473bb9d](https://github.com/ymusic-io/YMusicV4/commit/68473bb9d943d6d57bc0a527b13c8c91c915fc28))

### Telemetry
- Network/captcha/playability failures logged as WARN, not crash records ([c1940080e](https://github.com/ymusic-io/YMusicV4/commit/c1940080ee006d7bcbb1819309399de6fa5bc07a))
- Keep exception class names under R8 ([216e1f880](https://github.com/ymusic-io/YMusicV4/commit/216e1f8800ec2ef93e1cc21878c0c0f94d6aa70f))
- Sentry dist = versionCode ([f4b383a99](https://github.com/ymusic-io/YMusicV4/commit/f4b383a993873b6a7a8bc189afc99b86ea350a3b))
- Strip leaked tags from ANR/native crash events ([bbf6e0a1e](https://github.com/ymusic-io/YMusicV4/commit/bbf6e0a1ed9d4b1d00f05d94945d9b6039873129))
- Remote config 403 logged as WARN ([7807b77f2](https://github.com/ymusic-io/YMusicV4/commit/7807b77f2a8efe4a51a3110448514bd6131f6266))
- New diagnostics: MSE trail, base.js eval failure split E20150–E20153, E60051 stall stage, MSE reset reason ([795a70a74..0cbe58dc8](https://github.com/ymusic-io/YMusicV4/compare/066db7ff03747829975c93a305d2b690740a43ad...0cbe58dc8fc8c2db3dbfbbaa6a7a75894ad11a3e)); pot.ts E20148/E20149 ([41cc14b11](https://github.com/ymusic-io/YMusicV4/commit/41cc14b11ffa4d8fad0bd4b88e4883ae5a11d061))

### Accessibility
- Icon-only buttons announce as buttons to screen readers ([72a0bdeab](https://github.com/ymusic-io/YMusicV4/commit/72a0bdeab089aadf35c3c5e2ab7f6fb0b4718287))
- Player and sort toggles expose their state to screen readers ([d9bf93310](https://github.com/ymusic-io/YMusicV4/commit/d9bf9331020a6b22bd51a6d884fb9d7eefd566b7))

### Localization
- Replace hardcoded UI labels with en/vi/ar resources ([46bca8060..30a2e1c7b](https://github.com/ymusic-io/YMusicV4/compare/72a0bdeab089aadf35c3c5e2ab7f6fb0b4718287...30a2e1c7b594a7b625162be8de195ce13f2fc91c))
- Translate repeat-state and sort-direction labels ([11566d4c1](https://github.com/ymusic-io/YMusicV4/commit/11566d4c13d928035117f51abf9099d8fb50a306))

### Other
- Update dialog shows 7 lines of release notes ([a14fcb415](https://github.com/ymusic-io/YMusicV4/commit/a14fcb4155b2a348dc15482864b2a77e9317351e))

## 4.0.0 ([3bac880ac](https://github.com/ymusic-io/YMusicV4/commit/3bac880acdd9598051c3c706e72d62318be3b4c3))
- First release of YMusic V4.
