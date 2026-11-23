# Finding testers

Google lets a new personal developer account into production only after a
closed test with at least 12 testers who stay opted in for 14 days in a row.
Aim for 15–20. Testers need an Android phone and a Google account, not a
Scaleway account: the demo shows everything.

The page testers follow is `website/android-test.html`, published at
https://vandijkcloud.nl/Scaleway-ControlRoom/android-test.html. Fill in its
two links (the Google Group and the test's opt-in link) before sharing it.

## Setting up the test

1. Make a Google Group (groups.google.com) that anyone can join, for example
   `controlroom-testers`.
2. In the Play Console: Testing → Closed testing → create a track, add the
   group's email address as the tester list, and upload a bundle from the
   Android release workflow.
3. Copy the track's opt-in link and the group's link into
   `website/android-test.html`.

## Message for colleagues and friends

> Hi! I built an Android app for starting and stopping Scaleway servers, and
> Google wants 12 people to test it for two weeks before it may go into the
> Play Store. Would you help? You need an Android phone, not a Scaleway
> account: there is a demo. Joining takes two minutes, and the only thing you
> then have to do is keep the app installed for 14 days:
> https://vandijkcloud.nl/Scaleway-ControlRoom/android-test.html
> Thanks!

## Post for LinkedIn, Mastodon or the Scaleway community

> Looking for Android testers 🙋
>
> I've built ControlRoom, an app to start and stop Scaleway instances, read
> serverless logs and metrics, and see what your organization costs this
> month, straight from your phone. It talks only to Scaleway's API, with your
> own key. No backend, no tracking.
>
> Before it can go into the Play Store, Google wants 12 testers for 14 days.
> You don't need a Scaleway account: the built-in demo shows every feature,
> and Pro is free during the test. Joining takes two minutes:
> https://vandijkcloud.nl/Scaleway-ControlRoom/android-test.html
>
> Feedback very welcome, and reposts too. (Independent app, not made by Scaleway.)

## Tester swap groups

In groups where developers test each other's apps (such as r/AndroidClosedTesting),
offer to test theirs in return:

> ControlRoom (Scaleway cloud manager), needs 12 testers for 14 days. No
> account needed: there is a demo. Join:
> https://vandijkcloud.nl/Scaleway-ControlRoom/android-test.html
> Happy to test yours back; reply with your link.
