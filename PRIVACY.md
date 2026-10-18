# Privacy

Usage Meter for Grok is an unofficial app by Antonio Castellon (Castellon.CH). It is not affiliated with xAI.

## What the app does

The app shows the usage numbers from the official Grok dashboard after you sign in. Sign-in opens the official page inside the app. The app reads the usage text that page already shows.

## What stays on this device

The app saves the last percentages, product names, reset text, and the time it read them. That copy is stored in the app's private storage so the screen still works offline. It is excluded from Android backup. Sign out deletes it.

Sign-in uses the normal WebView cookie jar for grok.com and accounts.x.ai. The app does not copy those cookies to a server, and it does not ask you to type a password into a form that this app owns. If a sign-in page stays idle for three minutes, the app cancels the check and deletes those cookies. Switching to the Google or X prompt does not do that.

## What the app does not do

- It does not run a backend.
- It does not collect analytics.
- It does not see or store your password, payment method, or invoices.
- It does not invent usage numbers.

When the official page loads, your phone talks to xAI (grok.com, accounts.x.ai, and the sign-in providers that page uses) under your own account. That traffic is covered by xAI's own policy.

## Contact

Questions about privacy: antonio@castellon.ch
