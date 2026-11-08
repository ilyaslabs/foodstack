# 15 Card payments

[Back to the handbook](README.md)

FoodStack works with cash only out of the box. To take cards, connect a Stripe account. Card numbers never reach your server: customers type them on Stripe's own page (or in Stripe's own secure fields inside your app), so you stay in the simplest card-security category (PCI SAQ A).

## What you need

- A [Stripe](https://stripe.com) account for your business, activated for live payments in your country.
- About 20 minutes.
- Test first with Stripe's **test mode** keys (they start `sk_test_` and `pk_test_`), then switch to live keys (`sk_live_`, `pk_live_`).

## Steps

1. **Get your keys.** In the Stripe dashboard, open **Developers > API keys**. Copy the **Secret key** and the **Publishable key**.
2. **Tell Stripe where to report payments.** In **Developers > Webhooks**, click **Add endpoint**:
   - Endpoint URL: `https://api.example.com/api/v1/payment-webhooks/stripe`
   - Events: `checkout.session.completed`, `checkout.session.expired`, `checkout.session.async_payment_succeeded`, `checkout.session.async_payment_failed`

   Save, then copy the endpoint's **Signing secret** (it starts `whsec_`).
3. **Put the keys in FoodStack's settings**:
   ```bash
   cd /opt/foodstack
   nano .env
   ```
   ```
   PAYMENT_GATEWAY=stripe
   STRIPE_SECRET_KEY=<sk_...>
   STRIPE_WEBHOOK_SECRET=<whsec_...>
   STRIPE_PUBLISHABLE_KEY=<pk_...>
   ```
   Then:
   ```bash
   ./restart.sh server
   ```
   Update the copy of `.env` in your password manager.
4. **Switch card payment on for customers.** In the back office, open **Ordering**, switch on **Card**, choose how customers type their card (on Stripe's page, or in card fields inside the app), decide whether they may save a card, and save.

## Check it worked

1. In the customer app, put a dish in the cart and choose **Card** at checkout.
2. With test keys, pay with Stripe's test card `4242 4242 4242 4242`, any future date and any three digits.
3. The order page shows **Paid by card**, and the restaurant's **Live orders** page shows the order.
4. In the Stripe dashboard, **Payments** lists the payment, and **Developers > Webhooks** shows the events as delivered (answered 200).

When the test works, repeat steps 1 to 3 of **Steps** with the live keys and a new webhook endpoint in live mode.

## Undo

In the back office, switch **Card** off on the **Ordering** page: customers see cash only at once. To disconnect Stripe entirely, set `PAYMENT_GATEWAY=none` and run `./restart.sh server`. Orders already paid keep their payments and can still be refunded from the Stripe dashboard.

## If something goes wrong

| Problem | What to do |
|---|---|
| The server does not start after the change | `./logs.sh server` says which key is missing. Stripe needs both `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` |
| Card orders stay "Waiting for payment" after paying | Stripe's reports do not reach FoodStack. Check the webhook URL and the events in step 2, and that `STRIPE_WEBHOOK_SECRET` is that endpoint's secret. Unpaid orders fail by themselves after 30 minutes |
| Card is not offered at checkout | Switch it on in **Ordering**; the server must also have `PAYMENT_GATEWAY=stripe` |
| You want restaurants to receive card money directly | That is set per branch on its **Payments** tab, after you allow it on **Ordering**. The branch connects its own Stripe account; FoodStack keeps track of the commission it owes you |
| `PAYMENT_GATEWAY=sandbox` stops the server | The sandbox is a fake gateway for testing and is refused on a real install |
