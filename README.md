# Dengar

Live Indonesian → English translation for a phone held up in a conversation. One mode only:
listen to spoken Indonesian, show English on screen, and eventually a running gist of what is
being said, a slowed-down replay of recorded conversations, and a word bank built from them.

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
The remote sandbox cannot build Android; GitHub Actions is the compiler.
