# Donation removal: API 35 acceptance captures

These five captures were taken from the application source at commit `6b84f5a6fb373f4cff81d4fed0160e2cd5f4a98f` (tree `b591e30d9b3ab1b15bbfbf46725e12439cfd3124`) using a temporary Android test fixture on an API 35 emulator. The four acceptance cases passed with zero failures or skips. The test export used a temporary x86_64 packaging adjustment; the production source was unchanged. No live registration or payment was performed.

The test APK SHA-256 was `12f91d0c346d789a024aec9dcd5722c09ef098432b83045c7e6f823afc810ba5`; the application APK SHA-256 was `a735b86597d48ac6f675f8fa6d70514e16f14860c43006329f5e548658977daa`.

| Capture | Screen | SHA-256 |
| --- | --- | --- |
| [settings-top.png](settings-top.png) | Settings | `c010f1c18d93e3a68fdee0b28ca2161a5d9b559c44deacac82cd9307d7e8a5a4` |
| [settings-support.png](settings-support.png) | Settings and external support links | `3aeb52ae625a9b30505f3c24e03ae1e2b875a2893e63cf16bdb516228962e8bc` |
| [account.png](account.png) | Account settings | `1d76350f8b3d073e50e35da186bfc10c0249fead99766b552feb47ba2708250e` |
| [profile.png](profile.png) | Profile | `ba74adbf76256c25ab163be56b3cf875e3c44a0f59cdbbcc33353b3092dd96ab` |
| [backups.png](backups.png) | Backup settings | `28da15d1fec9d81b1a2399f033ce8951e30254ab7de67fa1152e5d5980e5ef56` |
