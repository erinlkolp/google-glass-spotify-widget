# TLS spike

Answers whether this AOSP 5.1.1 build's 2015-era CA trust store can reach Spotify.

Run 2026-08-09: **PASSED.** TLSv1.2, chain terminates at DigiCert Global Root G2
(issued 2013, so present in the store). `TlsFactory` and bundled PEM anchors are
therefore out of scope for this project.

Retained because the leaf expires 2027-02-20. If Spotify ever migrates to a root
issued after 2015, re-run this to confirm the diagnosis before building anything.

## Reproduce

    javac --release 8 -d classes TlsProbe.java
    $ANDROID_HOME/build-tools/34.0.0/d8 --min-api 22 --output . classes/spike/*.class
    adb -s 0123456789ABCDEF push classes.dex /data/local/tmp/tlsprobe.dex
    adb -s 0123456789ABCDEF shell "CLASSPATH=/data/local/tmp/tlsprobe.dex app_process32 /data/local/tmp spike.TlsProbe"
    adb -s 0123456789ABCDEF shell "rm -f /data/local/tmp/tlsprobe.dex"

Glass WiFi must be up first: `adb -s 0123456789ABCDEF shell svc wifi enable`.
No enums in the probe — d8 8.2.2-dev NPEs on enums compiled by JDK 21 javac.
