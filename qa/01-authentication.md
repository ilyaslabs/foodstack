# 01 Authentication

[Back to QA index](README.md)

Sign-up, every way to sign in, password reset, staying signed in, signing out, and two-step sign-in. The customer app and the back office share the same sign-in card, so test each case in the app it names.

Database: `foodstack_identity`. Events: `identity_outbox` and Kafka `foodstack.identity.events`.

## Sign-up

### AUTH-01 Create a customer account

Who: nobody signed in · Where: customer app

Steps
1. Open `http://localhost:4200/`, click **Sign in**, then **New here? Create an account**.
2. Enter your name `QA Customer One`, the email `qa1@example.test` and the password `qa-password-1`.
3. Click **Create account**.

Expect
- You are signed in and back on the home page. The header shows your name or account menu instead of **Sign in**.

Check
- `identity_users`, newest document (`db.identity_users.find().sort({createdAt:-1}).limit(1)`):
  - `displayName: "QA Customer One"` and `roles: ["CUSTOMER"]`.
  - `emailCipher` and `emailIndex` are filled, and you cannot read the email in either.
  - `passwordHash` starts with `{argon2}$argon2id$`.
  - `status: "ACTIVE"`, `tokenVersion: 1`, `brandIds` and `storeIds` empty. There is no `emailVerifiedAt`: signing up does not prove the email.
  - No `blockedAt`, no `totpEnabledAt`, no `deleteAfter`.
- `identity_login_keys`: one document whose `userId` is the new user's `_id`, and whose `loginKey` equals the user's `emailIndex`.
- `identity_outbox`: `type: "foodstack.identity.UserRegistered.v1"`, `subject` = the user's id, `status` turns `SENT`.
- `identity_refresh_tokens`: one document for the user with `client: "WEB"`, `expiresAt` 30 days from now and no `usedAt`.
- Browser (F12, Application, Cookies): a `fs_refresh` cookie with HttpOnly ticked, SameSite `Strict` and path `/api/v1/auth/refresh`. Local Storage holds no token, only a "signed in" hint.

### AUTH-02 Sign-up refuses bad input

Who: nobody signed in · Where: customer app, **Create your account**

Steps
1. Enter the email `qa1@example.test` again, with any valid name and password. Click **Create account**.
2. Enter a new email with the password `short`.
3. Enter `not-an-email` as the email.

Expect
1. "An account with this email already exists. Sign in instead."
2. "Use 10 to 128 characters for the password." The account is not made.
3. "Enter a valid email" shows under the field, and the button does nothing.

Check
- `identity_users.countDocuments()` is the same as after AUTH-01.

## Signing in

### AUTH-03 Sign in with email and password

Who: `customer@demo.foodstack.local` · Where: customer app

Steps
1. Click **Sign in**, then enter the email and the password `foodstack-demo`.
2. Click **Sign in**.

Expect
- You are signed in. **Orders** and **Account** are in the header menu.

Check
- `identity_refresh_tokens`: a new document for this user, `client: "WEB"`, no `usedAt`.
- `identity_audit_log` gets **no** line: customer sign-ins are not recorded.
- Browser Network tab: `POST /api/v1/auth/login` answers 200 with `accessToken` and `expiresIn: 600`. The response sets the `fs_refresh` cookie.

### AUTH-04 Back-office sign-in is recorded in the audit log

Who: `support@demo.foodstack.local` · Where: back office

Steps
1. Open `http://localhost:4300/admin/` and sign in as support.

Expect
- The Overview page opens. The side menu shows only what support may use (see USER-05).

Check
- `identity_audit_log`, newest line: `kind: "SIGN_IN"`, `actorName` is the support user's name, `roles: ["SUPPORT"]`, `area: "auth"`, `action: "password"`, `status: 200`, and `ip` is set.

### AUTH-05 Wrong password

Who: `support@demo.foodstack.local` · Where: back office

Steps
1. Sign in with the right email and the password `wrong-password-1`.

Expect
- "That email and password do not match." You stay on the sign-in card.

Check
- `identity_audit_log`, newest line: `kind: "SIGN_IN_FAILED"`, `status: 401`, `action: "password"`.
- Browser: `POST /api/v1/auth/login` answers 401 with code `LOGIN_FAILED`.

### AUTH-06 Unknown email looks the same as a wrong password

Who: nobody · Where: customer app

Steps
1. Sign in with `nobody-here@example.test` and any password.

Expect
- The same message as AUTH-05. Nothing tells you whether the email has an account.

### AUTH-07 Too many wrong passwords

Who: `qa1@example.test` (from AUTH-01) · Where: customer app

Steps
1. Enter a wrong password 10 times in a row.
2. Then enter the right password.

Expect
- From the 11th try, even with the right password: "Too many tries. Wait a few minutes and try again."
- After 15 minutes the right password works again.

Check
- Redis: a key `foodstack:identity:rl:password:<the user's emailIndex>:<window>` whose value is 10 or more, with a TTL of up to about 15 minutes.
- Browser: the answer is 429 with a `Retry-After` header.

### AUTH-08 Sign in with an emailed code

Who: `customer@demo.foodstack.local` · Where: customer app

Steps
1. Click **Sign in**, enter the email, then **Email me a code instead**, then **Email me a code**.
2. Open Mailpit and copy the 6-digit code from the newest email.
3. Enter it and click **Sign in**.

Expect
- After step 1: "If customer@demo.foodstack.local has an account, a code is on its way. It works for 5 minutes."
- Mailpit has the email with the code. You are signed in after step 3.

Check
- Redis, after step 1: a hash `foodstack:identity:code:LOGIN:<userId>` with a TTL of up to 300 seconds. It is gone after step 3, because a code works once.
- `identity_outbox`: `foodstack.identity.EmailCodeIssued.v1`. Its `data` holds the code encrypted, not in plain text.
- `foodstack_notification.notification_deliveries`, newest: `template: "login-code"`, `channel: "EMAIL"`, `status: "SENT"`, `recipient` encrypted. There is no `html` or `text`: a code email's body is wiped once sent, and only the subject stays.
- `identity_users`: the user now has `emailVerifiedAt` set (the code proved the email).

### AUTH-09 Wrong or used code

Who: `customer@demo.foodstack.local` · Where: customer app

Steps
1. Ask for a code (AUTH-08 step 1), then enter `000000`.
2. Enter the right code, sign in, sign out, and enter the same code again.
3. Ask for a code and enter a wrong one 5 times, then the right one.

Expect
- Each time: "That code is wrong or has expired."
- In step 3 the right code is refused too, because a code is dropped after 5 wrong tries.

### AUTH-10 Code for an unknown or blocked account

Who: nobody · Where: customer app

Steps
1. Ask for a code for `nobody-here@example.test`.

Expect
- The same "a code is on its way" message as AUTH-08.

Check
- Mailpit gets no email, and `identity_outbox` gets no `EmailCodeIssued`.

### AUTH-11 Reset a forgotten password

Who: `qa1@example.test` · Where: customer app

Steps
1. Sign in on two browsers (for example a normal and a private window) as `qa1@example.test`.
2. In the first, sign out, then click **Sign in**, then **Forgot password?**.
3. Enter the email and click **Email me a reset code**.
4. Take the code from Mailpit, enter it with the new password `qa-password-2`, and click **Set password and sign in**.
5. In the second browser, reload after a minute.

Expect
- Step 4 signs you in with the new password. The old password no longer works.
- Step 5: the second browser is signed out (it fails at its next token renewal, at most 10 minutes later; a reload shows it at once).

Check
- `identity_users`: `passwordHash` changed, `tokenVersion` is one higher, `emailVerifiedAt` is set.
- `identity_outbox`: `UserTokensRevoked` with reason `PASSWORD_RESET`.
- Redis: `foodstack:identity:revoked:<userId>` holds the new token version.
- `identity_refresh_tokens`: the second browser's token is refused at its next use.

### AUTH-12 Reset refuses a short password before spending the code

Who: `qa1@example.test` · Where: customer app

Steps
1. Ask for a reset code. Enter it with the new password `short`.
2. Enter the same code with a valid new password.

Expect
1. "Use 10 to 128 characters for the password."
2. Works: the code was not used up by the refused try.

## Staying signed in and signing out

### AUTH-13 The session survives a reload and renews itself

Who: `customer@demo.foodstack.local` · Where: customer app

Steps
1. Sign in, then reload the page.
2. Open a second tab of the app.
3. Leave the tab open for 12 minutes, then open **Orders**.

Expect
- You stay signed in in every step and are never asked for the password.

Check
- Browser Network tab: `POST /api/v1/auth/refresh` about a minute before each 10-minute token runs out, and on each reload. Each answer sets a new `fs_refresh` cookie.
- `identity_refresh_tokens`: each renewal marks the old document's `usedAt` and adds a new one with the same `familyId`.

### AUTH-14 A stolen refresh token is caught

Who: `customer@demo.foodstack.local` · Where: customer app, with the browser tools

Steps
1. Sign in. In F12, Application, Cookies, copy the value of `fs_refresh`.
2. Reload the page twice (the cookie changes each time).
3. Send the old value yourself:
   `curl -i -X POST http://localhost:8080/api/v1/auth/refresh -H "Cookie: fs_refresh=<old value>"`

Expect
- 401 with code `REFRESH_REUSED`.
- The browser is signed out at its next renewal, because the whole sign-in was ended.

Check
- `identity_refresh_tokens`: no document left with this sign-in's `familyId`.

### AUTH-15 Sign out

Who: `customer@demo.foodstack.local` · Where: customer app

Steps
1. Sign in, then **Account menu > Sign out**.
2. Press the browser's Back button.

Expect
- You are signed out, and Back does not show private pages.

Check
- `identity_refresh_tokens`: no documents left with that `familyId`.
- The `fs_refresh` cookie is gone.

### AUTH-16 Sign out everywhere

Who: `customer@demo.foodstack.local` · Where: customer app, two browsers

Steps
1. Sign in on two browsers.
2. In one, open **Account**, and under **Security** click **Sign out on all devices**.
3. In the other, click anything that loads data (for example **Orders**).

Expect
- Both browsers end up signed out within seconds.

Check
- `identity_users`: `tokenVersion` is one higher.
- `identity_outbox`: `UserTokensRevoked` with reason `LOGOUT_EVERYWHERE`.
- Redis: `foodstack:identity:revoked:<userId>` = the new version.

### AUTH-17 Change password while signed in

Who: `qa1@example.test` · Where: customer app, **Account > Security > Change password**

Steps
1. Change the password with a wrong current password.
2. Change it with the right current password to `qa-password-3`.

3. In the back office as `qa.finance@example.test` (USER-02), open the account menu (your initials, top right) and **Change password**. Try two different new passwords, then a wrong current password, then do it properly.

Expect
1. "The current password is not correct."
2. "Your password is changed." The next sign-in needs the new password.
3. "The two new passwords are not the same." without asking the server, then "Your current password is not correct.", then "Your password is changed. Use the new one next time you sign in."

Check
- `identity_users`: `passwordHash` changed.
- Network: `PUT /api/v1/me/password` answers 204, or 400 `PASSWORD_WRONG`.

### AUTH-18 Blocked account

Who: admin, then `qa1@example.test` · Where: back office, then customer app

Steps
1. Sign in as `qa1@example.test` in the customer app.
2. In the back office, as admin, open **Users**, find QA Customer One, open the row's menu (⋮), click **Block** and give the reason `QA test`.
3. In the customer app, reload, then try to sign in again.

Expect
- The open session ends within seconds.
- Signing in shows "This account is blocked. Contact support."

Check
- `identity_users`: `status: "BLOCKED"`, with `blockedAt`, `blockedBy` (the admin's id) and `blockedReason: "QA test"` set, and `tokenVersion` is higher.
- Unblock afterwards (USER-07) so later cases can use the account.

## Two-step sign-in (back office)

### AUTH-19 Turn on two-step sign-in

Who: `support@demo.foodstack.local` · Where: back office

You need an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, or a desktop one such as KeePassXC).

Steps
1. Open **Account menu > Two-step sign-in** and click **Turn on**.
2. Scan the QR code, or type the key shown under **Can't scan it?**.
3. Enter the 6-digit code the app shows and click **Verify**.
4. Copy or download the 10 recovery codes, then close the dialog.

Expect
- The page shows two-step sign-in as on.
- The 10 recovery codes are shown once only. Reopening the page does not show them again.

Check
- After step 1: `identity_users` has `totpPendingCipher` set and no `totpEnabledAt`.
- After step 3: `totpSecretCipher` and `totpEnabledAt` are set, and `totpPendingCipher` is gone.
- `recoveryCodeHashes` has 10 entries, all hashes. None matches a code you were shown.

### AUTH-20 Sign in with the authenticator code

Who: `support@demo.foodstack.local` (after AUTH-19) · Where: back office

Steps
1. Sign out, then sign in with email and password.
2. When asked, enter the current code from the app.
3. Sign out and sign in again, entering the **same** code within the same 30 seconds.

Expect
- Step 2: "Two-step sign-in" asks for the code, then the Overview page opens.
- Step 3: "That code is not correct. Check the app and try again." A code works only once. The next code works.

Check
- Browser: `POST /api/v1/auth/login` answers 200 with `{"next":"TOTP","challenge":"…"}` and no token. `POST /api/v1/auth/two-step` then returns the token.
- `identity_users.totpLastStep` grows with each successful code.
- `identity_audit_log`: `kind: "SIGN_IN"`, `action: "password + authenticator"`.

### AUTH-21 Sign in with a recovery code

Who: `support@demo.foodstack.local` · Where: back office

Steps
1. Sign in with the password, then click **Lost your phone? Enter one of your recovery codes instead.**
2. Enter one recovery code from AUTH-19.
3. Sign out and try the same recovery code again.

Expect
- Step 2 signs you in. Step 3 is refused, because each recovery code works once.

Check
- `recoveryCodeHashes` has 9 entries.

### AUTH-22 A role that requires two-step sign-in

Who: a new FINANCE user · Where: back office

This case needs ADMIN or FINANCE to require two-step sign-in, as on a real install. In the local run, start the server with `--foodstack.identity.totp.required-roles=ADMIN,FINANCE`, or test it on the Docker Compose trial (OPS-01).

Steps
1. As admin, create a user with role Finance (USER-02).
2. Sign in as that user with email and password.
3. Follow **Set up two-step sign-in**: scan, enter the code, save the recovery codes.

Expect
- You cannot reach any back-office page before finishing setup.
- After step 3 you land on the Overview page.
- On **Account menu > Two-step sign-in** there is no **Turn off** for this role.

Check
- Browser: login answers `{"next":"TOTP_ENROLL", …}`.
- `POST /api/v1/me/two-step/disable` answers 403 with code `TOTP_NOT_ALLOWED`.

### AUTH-23 New recovery codes, and turning two-step sign-in off

Who: `support@demo.foodstack.local` · Where: back office, **Account menu > Two-step sign-in**

Steps
1. Click **Get new codes** and enter the current authenticator code.
2. Click **Turn off** and enter the current code.

Expect
1. 10 new codes. The old ones stop working.
2. The next sign-in asks for the password only.

Check
- After step 2: `totpSecretCipher`, `totpEnabledAt` and `recoveryCodeHashes` are gone from `identity_users`.

### AUTH-24 Administrator resets someone's two-step sign-in

Who: admin, for a user with two-step sign-in on · Where: back office, **Users**

Steps
1. Turn on two-step sign-in for support (AUTH-19) and leave support signed in in another browser.
2. As admin, open **Users**, open the support user's row menu (⋮), click **Reset two-step sign-in**, then **Reset**.

Expect
- Support's open session ends.
- Support's next sign-in needs only the password, or, for a role that requires it, sets it up again.

Check
- `identity_users`: the TOTP fields are gone and `tokenVersion` is higher.
- `identity_outbox`: `UserTokensRevoked` with reason `TOTP_RESET`.

## Google and Apple

These need real client ids from Google or Apple, and a domain they accept. Test them on a staging install with HTTPS. In the local run, test only AUTH-25 and AUTH-28.

### AUTH-25 Buttons appear only when switched on

Who: admin · Where: back office, **Settings > Sign-in options**, then the customer app

Steps
1. With no client ids set, open the customer app's sign-in card.
2. As admin, enter a Google client id (for example `1234-abc.apps.googleusercontent.com`) and save.
3. Reload the customer app's sign-in card (allow up to 5 minutes for caches).
4. Enter `bad id!` as a client id and save.

Expect
1. No Google or Apple button.
2. Saved.
3. **Continue with Google** shows. Apple shows only if an Apple id is set.
4. Refused: "Client ids are letters, digits, dots and dashes (at most 10)".

Check
- `identity_settings`: document `_id: "sign-in"` with `googleClientIds: ["1234-abc.apps.googleusercontent.com"]`, `updatedBy` = the admin's id, and `updatedAt`.
- `GET /api/v1/auth/providers` returns the ids and a `Cache-Control: max-age=300, public` header.

### AUTH-26 First Google sign-in makes a customer

Who: a Google account that has no FoodStack account · Where: customer app (staging)

Steps
1. Click **Continue with Google** and finish Google's popup.

Expect
- You are signed in, under the name Google gives.

Check
- `identity_users`: new user, `roles: ["CUSTOMER"]`, `emailVerifiedAt` set, `socialLogins` has a `google` entry, and there is no `passwordHash`.
- `identity_login_keys`: two keys for the user, one for the email and one for the Google account.

### AUTH-27 Google sign-in to an existing account

Who: a Google account whose email already has a FoodStack account · Where: customer app (staging)

Steps
1. **A. Proven email.** Sign in with an emailed code once (AUTH-08), sign out, then use **Continue with Google** with the same email.
2. **B. Unproven email.** Make an account by password sign-up (AUTH-01) and do *not* sign in with a code. Then use **Continue with Google** with the same email.

Expect
- A: you are signed in to the same account, and the password still works.
- B: you are signed in to the same account, but the old password **stops working**. Whoever signed up with that email might not have been its owner.

Check
- B: `identity_users` has no `passwordHash` and a higher `tokenVersion`. `identity_outbox` has `UserTokensRevoked` with reason `SOCIAL_LINKED`.

### AUTH-28 Forged Google token

Who: nobody · Where: terminal

Steps
1. `curl -i -X POST http://localhost:8080/api/v1/auth/social -H 'Content-Type: application/json' -d '{"provider":"google","idToken":"abc.def.ghi","client":"web"}'`

Expect
- 401 with code `SOCIAL_TOKEN_INVALID`, or `SOCIAL_PROVIDER_OFF` when no Google id is set. No user is made.

## Mobile clients (API only)

### AUTH-29 Refresh token in the body for apps

Who: `customer@demo.foodstack.local` · Where: terminal

Steps
1. `curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"client":"mobile","email":"customer@demo.foodstack.local","password":"foodstack-demo"}'`
2. Send the `refreshToken` from the answer: `curl -s -X POST http://localhost:8080/api/v1/auth/refresh -H 'Content-Type: application/json' -d '{"refreshToken":"<token>"}'`
3. Send the same token as a cookie instead: `curl -i -X POST http://localhost:8080/api/v1/auth/refresh -H "Cookie: fs_refresh=<token from step 2's answer>"`

Expect
1. The answer has `accessToken` and `refreshToken` in the body, and no cookie.
2. A new pair.
3. 401 with code `REFRESH_WRONG_CHANNEL`.

Check
- `identity_refresh_tokens`: `client: "MOBILE"`.
