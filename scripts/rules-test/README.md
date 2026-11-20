# firestore.rules in the emulator

`firestore.rules` exercised against the Firestore emulator: the shared limit, groups, group
codes, progress, entitlements, and the account-deletion sequence of
`web/app/account-delete.js`, run as the real module through the real SDK.

Needs Node and Java 21+ (the emulator is a JAR). From this folder:

```bash
npm install
npm test
```

`npm test` starts the emulator on port 8085 with the `demo-thumbtrek` project (a `demo-`
id never reaches a real project), runs `node --test`, and shuts it down. The tests load
`../../firestore.rules` themselves, so editing the rules needs no other step.
