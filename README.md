# Dengar

Live Indonesian → English translation for a phone held up in a conversation, plus a voice
recorder for every conversation so it can be replayed slowly afterwards.

- **Listen**: tap the mic. Audio is streamed to OpenAI's Realtime transcription API
  (Indonesian, `gpt-4o-transcribe`) and each utterance is translated on-device with ML Kit.
  English is shown large, the Indonesian underneath.
- **Record**: the same audio is saved as a 24 kHz WAV next to a timestamped transcript in
  `filesDir/conversations/<id>/`, so the original voice is always there when the transcript is wrong.
- **Replay**: past conversations play back at 0.5×–1×; tap a line to hear it, repeat a line on loop.
- **Settings**: paste an OpenAI API key once. It never leaves the phone.

Later: a running gist of what is being said, and a word bank built from real conversations.

`applicationId`: `ca.skopek.dengar`

## Installing updates on a phone

Every push builds a signed release APK and publishes it as a GitHub Release tagged
`v0.1.<build number>`. The easiest way to keep a phone up to date is
[Obtainium](https://github.com/ImranR98/Obtainium): add this repository's URL as an app and it
will notify you when a new release appears and install it with one tap.

Signing needs four repository secrets (Settings → Secrets and variables → Actions):

| Secret              | Value                                   |
|---------------------|-----------------------------------------|
| `KEYSTORE_BASE64`   | the release keystore, base64 encoded    |
| `KEYSTORE_PASSWORD` | keystore password                       |
| `KEY_ALIAS`         | key alias inside the keystore           |
| `KEY_PASSWORD`      | key password                            |

Keep the keystore safe: an APK signed with a different key cannot update an installed app.
Local `assembleRelease` builds without these variables fall back to the debug key.

## Development

The pipeline and conventions follow [docs/new-android-app-playbook.md](docs/new-android-app-playbook.md).
The remote sandbox cannot build Android; GitHub Actions is the compiler. Pure-Kotlin packages
(`transcript`, `audio` minus `AudioCapture`, `realtime` minus `RealtimeTranscriber`, `store`) are
unit tested locally with a throwaway Kotlin/JVM project before every push.
