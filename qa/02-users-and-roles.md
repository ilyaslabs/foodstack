# 02 Users and roles

[Back to QA index](README.md)

Who can see and do what in the back office, how back-office accounts are made, blocking, and the audit log.

Database: `foodstack_identity`.

## The roles

| Role | Made by | What they may do |
|---|---|---|
| ADMIN | First start (`bootstrap-admin`), or another admin | Everything |
| FINANCE | An admin | Users (read), payments, payouts and settlements, store payment collection |
| SUPPORT | An admin | Users (read), orders (cancel, refund), dispatch, review moderation |
| BRAND_OWNER | Admin, on the brand page (**Managers** tab) | Their brand's branches, menu, orders, payouts, merchant account |
| STORE_OWNER | Admin or the brand owner, on the branch page (**Managers** tab) | One branch: its orders, menu availability, riders, payouts |
| STORE_STAFF | Same as store owner | The branch's live orders |
| RIDER | Admin, on **Riders** | Rider pages: offers, deliveries, earnings |
| STORE_RIDER | The branch, on its **Riders** tab | Rider pages for that branch's deliveries only |
| CUSTOMER | Sign-up, or Google or Apple | The customer app only |

## Making back-office accounts

### USER-01 Back-office accounts have no sign-up

Who: nobody · Where: back office

Steps
1. Open `http://localhost:4300/admin/sign-in`.

Expect
- There is no **Create account** link. Only the customer app has sign-up.

### USER-02 Create a Finance or Support account

Who: admin · Where: back office, **Users**

Owners and riders are not made here: they are added from their branch page and from **Riders** (USER-03, DLV-01).

Steps
1. Click **New staff account**.
2. Click **Make account** with the fields empty, then with a first password of 5 characters.
3. Fill in **Full name** `QA Finance`, **Email** `qa.finance@example.test`, **First password** `qa-finance-pass-1`, pick **Finance** under **Role**, and click **Make account**.
4. Do it again with the same email.
5. Sign in to the back office as `qa.finance@example.test` and change the password (AUTH-17, step 3).

Expect
- The dialog says "For your own team. Restaurant owners are added on their branch page, and riders on Riders." Each role says what it may do.
- Step 2: "Fill in the name and the email.", then "The first password needs at least 10 characters."
- Step 3: "QA Finance can sign in now". The account is at the top of the list as **Finance**.
- Step 4: "An account with this email already exists. Find it in the list instead."
- Step 5: the side menu shows Overview, Finance and Users, and nothing else. On a real install, the first sign-in asks to set up an authenticator app (Finance needs two-step sign-in).
- As support, the **New staff account** button is not there.

Check
- `identity_users`: `roles: ["FINANCE"]`, `createdBy` = the admin's id (not the user's own id as at sign-up), `passwordHash` an Argon2id hash.
- `identity_outbox`: `UserRegistered` with `createdBy` = the admin.
- Network: `POST /api/v1/users` answers 201. With a support user's token it answers 403. With `"roles":["BRAND_OWNER"]` (or `RIDER`, `CUSTOMER`) it answers 400 `USER_ROLES_INVALID`. An email in use answers 409 `EMAIL_TAKEN`.

### USER-03 Add a store owner from the branch page

Who: admin · Where: back office, **Stores**, a branch, **Managers** tab

Steps
1. Open Napoli Slice (DHA Phase 5), then the **Managers** tab, then add `qa.owner@example.test` named `QA Owner` with a starting password.
2. Sign in as `qa.owner@example.test`.

Expect
- The owner is listed.
- Signed in, the side menu shows **Live orders**, **Orders**, **My store**, **Coupons** and **Payouts**, for that branch only.

Check
- `identity_users`: `roles: ["STORE_OWNER"]` and `storeIds: ["0195f0a0-0000-7000-8000-000000000201"]` (the branch id).
- The access token (paste it into jwt.io) has `storeIds` and `perms` such as `store:portal`.

### USER-04 Remove an owner

Who: admin · Where: the branch's **Managers** tab

Steps
1. Keep `qa.owner@example.test` signed in in another browser.
2. Click **Remove access** for the owner.
3. In the owner's browser, click **Live orders**.

Expect
- The owner loses access within seconds (signed out, or "no access").

Check
- `identity_users`: the branch id is gone from `storeIds`, and `tokenVersion` is higher.
- `identity_outbox`: `UserTokensRevoked` with reason `STORE_ACCESS_REVOKED`.

## What each role sees

### USER-05 Side menu per role

Who: each test account in turn · Where: back office

Steps
1. Sign in as each account and write down the side menu.

Expect

| Account | Menu |
|---|---|
| admin | Overview, Live orders, Orders, Dispatch, Riders, Stores, Price approvals, Delivery pricing, Cities, Coupons, Finance, Reviews, Users, Languages, Branding, Sign-in options, Ordering, Rider pay, Messages, Email log, Failed events, Audit log |
| finance | Overview, Finance, Users |
| support | Overview, Live orders, Orders, Dispatch, Reviews, Users, Email log |
| spiceroute (brand owner) | Overview, Live orders, Orders, Branches, Coupons, Payouts |
| napoli (branch manager) | Overview, Live orders, Orders, My store, Coupons, Payouts |
| rider | Overview, Deliveries, Earnings |

- No account sees a menu item it cannot use.

### USER-06 A page you may not use is refused by the server too

Who: support · Where: terminal

Steps
1. With support's access token, call:
   - `GET /api/v1/settings/dead-letters` (settings)
   - `PUT /api/v1/settings/ordering` (settings)
   - `GET /api/v1/ledger/payouts` (finance)

Expect
- Each answers 403. Hiding the menu is not the only protection.

### USER-07 A store owner sees only their own branches

Who: napoli · Where: back office and terminal

Steps
1. Open **Orders**: only Napoli Slice orders are listed.
2. With napoli's token, call `GET /api/v1/orders/stores/0195f0a0-0000-7000-8000-000000000101/board` (a Spice Route branch).

Expect
- Step 2 answers 403.

## Blocking

### USER-08 Block and unblock

Who: admin · Where: back office, **Users**

Steps
1. Search for `QA Customer One` (or filter **Role: Customer**).
2. Row menu (⋮) **Block**, with the reason `QA test`.
3. Filter **Blocked**: the user is listed.
4. Row menu (⋮) **Unblock**.

Expect
- Blocking signs the user out everywhere at once and stops sign-in (AUTH-18). Their orders and data stay.
- After unblocking, they can sign in again.

Check
- After step 2: `status: "BLOCKED"`, `blockedAt`, `blockedBy` and `blockedReason` are set.
- After step 4: `status: "ACTIVE"`, and the blocked fields are gone.

### USER-09 Guard rails

Who: admin · Where: back office and terminal

Steps
1. Try to block yourself.
2. Try to block someone without a reason (API: `POST /api/v1/users/<id>/block` with `{}`).

Expect
1. Refused: `CANNOT_BLOCK_SELF`.
2. Refused: `BLOCK_REASON_REQUIRED`.

## Audit log

### USER-10 Every back-office change is recorded

Who: admin · Where: back office, **Audit log**

Steps
1. As admin, change something small, for example the tagline on **Branding**.
2. Open **Audit log**.

Expect
- The newest line shows you, the time, **Settings**, the endpoint `PUT /api/v1/settings/branding`, status 200, and before and after values.
- Sign-ins (AUTH-04) and failed sign-ins (AUTH-05) are listed too.

Check
- `identity_audit_log`, newest: `kind: "WRITE"`, `actorId` = admin, `area: "settings"`, `status: 200`, `traceId` set, and `before` and `after` filled. Passwords and codes never appear in `request`.

### USER-11 Only staff may read the audit log

Who: spiceroute · Where: terminal

Steps
1. With the brand owner's token, call `GET /api/v1/audit`.

Expect
- 403.
