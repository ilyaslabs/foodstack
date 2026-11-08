# 17 First sign-in and your team

[Back to the handbook](README.md)

Sign in to the back office for the first time, protect your account, and make accounts for the people who help you run the marketplace.

## What you need

- The address, email and password the installer printed ([guide 14](14-install.md)).
- Your phone with an **authenticator app** (Google Authenticator, Microsoft Authenticator, 1Password, Authy or similar).
- Email working ([guide 15](15-email.md)), so your staff can reset forgotten passwords.
- About 15 minutes.

## Steps

### Sign in and set up two-step sign-in

1. Open `https://admin.example.com/admin/` and sign in with the email and password from the installer.
2. FoodStack asks you to **set up an authenticator app**: administrators and finance staff must use one. Open the app on your phone, add an account, and scan the QR code on the screen (or type the key shown under it).
3. Type the 6-digit code the app shows and confirm.
4. FoodStack shows ten **recovery codes**. Each one lets you in once if you lose your phone. Save them in your password manager now: they are not shown again.

### Choose your own password

5. Click your initials at the top right, then **Change password**.
6. Type the installer's password as **Current password**, and a new one (at least 10 characters, ideally a long one from your password manager) twice. Click **Change password**.

### Make accounts for your team

7. Open **Users** in the menu on the left, then **New staff account**.
8. Fill in their name, their email and a first password, and choose a **Role**:

   | Role | Can |
   |---|---|
   | Support | See and help with orders, refund customers, assign riders, moderate reviews, read the email log |
   | Finance | See payouts, settle balances with restaurants and riders, set how branches collect card payments |
   | Admin | Everything, including settings and other people's accounts |

   Give each person the smallest role that lets them do their job.
9. Click **Make account**, and give them the first password in person or through your password manager's sharing. Ask them to change it at their first sign-in (step 5). Finance and Admin staff set up their authenticator app on their first sign-in, as you did.

Restaurant owners and riders are **not** made here: you add them from the restaurant's page ([guide 19](19-first-store.md)) and from **Riders**.

## Check it worked

- Sign out (initials > **Sign out**) and sign in again: FoodStack asks for your new password, then a code from your app.
- In **Users**, your team's accounts are listed with their roles.
- A Support colleague who signs in sees only Overview, Live orders, Orders, Dispatch, Reviews, Users and Email log in the menu.

## Undo

- **A staff member leaves**: in **Users**, open the menu (⋮) on their row and click **Block**, with a reason. They are signed out everywhere at once and cannot sign in again. Their past actions stay in the **Audit log**.
- **Someone lost their phone and recovery codes**: on their row, **Reset two-step sign-in**. Check it really is them first (call them). They set up a new app at their next sign-in.
- **You lost your own phone**: sign in with one of your recovery codes instead of the app's code, then set up the app again from initials > **Two-step sign-in**.

## If something goes wrong

| Problem | What to do |
|---|---|
| "That email and password do not match." | Copy the password from `/opt/foodstack/.env` (the line `FOODSTACK_ADMIN_PASSWORD=`). It is only the *first* password: once you change it, the file no longer matters |
| The app's code is refused | The phone's clock must be right: turn on automatic date and time on the phone |
| Too many tries | After 10 wrong passwords in 15 minutes, FoodStack waits before letting that account try again. Wait 15 minutes |
| "An account with this email already exists." | The person already has an account (for example as a customer). FoodStack does not turn a customer into staff; ask them to use another email for work |
| You are locked out completely (no password, no phone, no codes) | Another admin can reset your two-step sign-in. If you are the only admin, use "Forgot password?" for the password; for the phone, contact the FoodStack team |
