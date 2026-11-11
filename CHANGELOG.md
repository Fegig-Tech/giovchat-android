# Changelog

All notable changes to the Giov Android SDK. The format follows Keep a Changelog; versions follow semantic versioning.

## [Unreleased]

## [0.1.0]

First public release.

### Added

- `giovchat-core`: the visitor protocol client for the JVM and Android: widget configuration, resumable sessions
  with a pluggable secure `GiovSessionStore`, realtime socket with reconnect and history catch-up, the outbox
  (sending / sent / failed, retry, discard), GiovBot streaming drafts, typing, identity (`userId` + `userHash`),
  attachment policy checks, uploads with progress and authenticated downloads, unread counts (`GiovUnread`), FCM push
  registration, five languages and the chat markdown parser.
- `giovchat-compose`: `GiovChat(...)`, a Jetpack Compose chat screen on top of the core, with a controller or a
  `tenantKey` overload, `rememberGiovChatController` and `GiovLifecycleEffect`. Draws every phase (loading skeleton,
  pre-chat form, greeting, connecting, active, error with retry), the header identity (person, bot, team) with avatars,
  markdown bubbles with http(s) / mailto links, sender labels, failed-message retry and discard, a streaming draft
  replaced in place by the saved reply, the typing indicator (still when animations are off), attachments through an
  app-provided picker (policy problems, upload progress, image placeholders, file rows), a multiline composer, the
  "Powered by GiovChat" footer when the workspace shows it, light / dark / auto from the dashboard with the brand
  colour, style overrides, locale and string overrides, safe-area handling and accessibility (48 dp targets, content
  descriptions, font scaling up to 2x).
- `sample`: a runnable app with an unread badge, a session store encrypted with an Android Keystore key, a document picker and
  commented FCM registration.
- JVM Compose UI tests (Robolectric) covering the phases, sending, failure, streaming, links, header switching,
  branding, themes, lifecycle, attachments and tap-target sizes.
- The header links to the workspace's privacy policy (`privacyPolicyUrl`, else Giov's), as the web widget does.
- `GiovImagePlaceholder.create(bitmap)` makes the upload placeholder, drawing transparent images over white.
- `GiovOkHttp.client`: the one OkHttp client every default (HTTP, sockets, images) shares.

### Fixed

Found in the pre-release review:

- `GiovChat(tenantKey = ...)` without a `sessionStore` lost the conversation on every configuration change (rotation,
  dark mode, resizing): its in-memory store was remembered in the composition. The default store now lives as long as
  the process, as documented.
- `unregisterPushToken` did nothing while the chat was closed (the usual moment of sign-out), so the device kept
  receiving the previous visitor's replies. It now uses the stored session and a fresh visitor token.
- Push registrations now send `X-Giov-Client`, which the server stores with the device.
- A `typing_end` for the other kind (person or GiovBot) no longer hides the typing indicator.
- An unknown socket `error` code fails the oldest message still sending, like `invalid_message`, instead of
  resending it for ever.
- A history catch-up that fails after a reconnect (or on return to the foreground) still resends the outbox; a dead
  session there ends the chat and forgets the stored session.
- Upload failures keep their meaning: 404 forgets the conversation, 401/403 ends the session, 429 is a rate limit,
  5xx a server error; only other refusals are attachment rejections.
- The composer cuts a paste over 2000 characters (UTF-16 units, as the server counts) instead of ignoring it, never
  inside a surrogate pair, and stays editable while files upload.
- `start()` on a started controller does nothing (no dropped socket, no lost streaming draft); `retry()` restarts.
- `GiovChat(tenantKey = ...)` / `rememberGiovChatController` keep the controller in a ViewModel, so a configuration
  change keeps the socket, the outbox and the conversation.
- The last registered push token is registered again for the visitor's next conversation; `reset()` forgets it and
  resolves a registration still waiting for a conversation.
- A double tap on "Start chat" starts one conversation.
- Images load again when their URL changes, and the download token is renewed before it expires on long sessions.
- A first connection that fails retries with the protocol backoff instead of stopping at "Try again"; a socket that
  is coming back shows "Connection lost. Reconnecting…".
- Text on the brand colour is white or black by WCAG contrast; sender labels and times on agent bubbles meet 4.5:1.
- The stored session is keyed by workspace, `userId` and `sessionKey` only, not by name, email or phone.
