# Register release SHA-1 for Google Sign-In (one-time)

Firebase is not logged in on this machine, so this step needs you in the browser.
Without it, release APKs show **No credentials available**.

## Exact steps

1. Open:
   https://console.firebase.google.com/project/thumb-trek/settings/general/android:com.thumbtrek.app

2. Under **SHA certificate fingerprints** → **Add fingerprint**, paste:

   ```
   E4:38:D9:CB:C2:BC:7F:5B:55:6F:A5:A1:C0:96:48:EB:61:CB:DC:77
   ```

   Keep the existing debug SHA as well.

3. Download the updated `google-services.json` and replace:
   `app/google-services.json`

4. Update the GitHub Actions secret (from repo root, Git Bash / WSL):

   ```bash
   gh secret set GOOGLE_SERVICES_JSON < app/google-services.json
   ```

5. Rebuild / reinstall the release APK (or wait for the next GitHub Release).

Sign-in works only after step 2+3 are done — editing local code alone cannot invent the OAuth client Google creates from that SHA.
