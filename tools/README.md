# Token bootstrap

Glass has no keyboard, so Spotify authorization happens once on the laptop.

## First time only

1. Create an app at https://developer.spotify.com/dashboard
2. Add redirect URI exactly: `http://127.0.0.1:8888/callback`
   (Loopback literals are the only non-HTTPS redirects Spotify still allows.
   `localhost` is rejected; it must be `127.0.0.1`.)
3. Copy the Client ID. PKCE uses no client secret.

## Run it

    python3 tools/bootstrap_token.py <client-id>
    adb -s 0123456789ABCDEF push tools/refresh_token.txt /data/local/tmp/spotify_bootstrap_token

Launch Spotify on the Glass. It imports the token and deletes the pushed copy.

`tools/refresh_token.txt` is gitignored. After the app has imported it once, the
copy on the laptop is stale — Spotify rotates the refresh token on every refresh.
Delete it.

## If you ever see "Re-authorize on laptop"

The refresh token was invalidated. Re-run the two commands above.
