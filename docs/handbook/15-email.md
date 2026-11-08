# 15 Email

[Back to the handbook](README.md)

FoodStack emails order updates to customers, new orders to restaurants, sign-in codes, password resets and alerts to your staff. Until email is set up, nothing is emailed: people still see updates in the apps, but **sign-in by emailed code and "Forgot password?" do not work**.

## What you need

- An account with an email-sending service that offers **SMTP** (for example Amazon SES, Postmark, Mailgun, SendGrid, Brevo, or your own company's mail server).
- From it: the SMTP **host**, **port** (usually 587), **username** and **password**.
- An address to send from, on your own domain, such as `orders@example.com`. The service will ask you to prove you own the domain by adding a few DNS records (SPF, DKIM): do that first, in the same place as [guide 11](11-domain-and-dns.md), or your emails will land in spam.
- About 20 minutes, plus the service's own setup time.

## Steps

1. Open the settings file on the server:
   ```bash
   cd /opt/foodstack
   nano .env
   ```
2. Find the lines starting with `SMTP_` and fill them in:
   ```
   SMTP_HOST=<smtp host, such as email-smtp.eu-west-1.amazonaws.com>
   SMTP_PORT=587
   SMTP_USERNAME=<smtp username>
   SMTP_PASSWORD=<smtp password>
   SMTP_AUTH=true
   SMTP_STARTTLS=true
   SMTP_FROM=orders@example.com
   SMTP_FROM_NAME=<your marketplace's name>
   ```
   Save with Ctrl+O, Enter, and leave with Ctrl+X.
3. Restart the server part of FoodStack so it reads the new settings:
   ```bash
   ./restart.sh server
   ```
   It takes about a minute to come back.
4. Update the copy of `.env` in your password manager.

## Check it worked

1. Open `https://admin.example.com/admin/` in a private browser window, click **Email me a sign-in code** (or **Forgot password?**), and enter your email.
2. The email arrives within a minute. Check the spam folder too.
3. In the back office, open **Email log**: the email is listed as sent.

## Undo

Empty the `SMTP_HOST` line again and run `./restart.sh server`. Emails stop; the **Email log** lists new ones as not sent.

## If something goes wrong

| Problem | What to do |
|---|---|
| The **Email log** shows the email as failed, with an error | Read the error. "Authentication failed": wrong username or password. "Connection refused" or "timed out": wrong host or port, or the provider blocks port 587 (some cloud providers block outgoing mail ports until you ask them). Fix `.env`, restart, then click **Send again** on the failed email |
| The log says sent, but nothing arrives | Look in spam. Finish the sending service's domain checks (SPF and DKIM records). Some services keep new accounts in a "sandbox" that only sends to verified addresses: ask them to lift it |
| The log says "Email is not set up (SMTP_HOST)" | `SMTP_HOST` or `SMTP_FROM` is empty, or the server was not restarted |
| Sign-in codes are not in the log text | That is on purpose: codes are secret, so the log shows only the subject |
