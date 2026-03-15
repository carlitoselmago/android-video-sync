# android-video-sync
A follower player for android devices for looped sync playback

## Test media

`video.mp4` is bundled into the app as an asset fallback. If the leader broadcasts `video.mp4`, the app can play it even when `/storage/emulated/0/Movies/video.mp4` is absent.

## Test leader script

Run this from the project root to simulate a leader broadcaster:

```bash
python scripts/send_leader.py --filename video.mp4
```
