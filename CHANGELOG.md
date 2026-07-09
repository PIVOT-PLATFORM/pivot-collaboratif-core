## [0.1.2](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.1.1...v0.1.2) (2026-07-09)


### Bug Fixes

* **api:** add missing REST CORS config (Authorization header has no preflight support) ([#50](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/50)) ([0f2bad7](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/0f2bad767d3e415e755257aad25762d9ee89ac78))

## [0.1.1](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.1.0...v0.1.1) (2026-07-09)


### Bug Fixes

* **ci:** GHCR image path doubled the repo segment, missing semver tag ([#49](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/49)) ([5e0ed0d](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/5e0ed0d672ca7182c025c4cf9b6f65997d826d35)), closes [#198](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/198) [#128](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/128)

# [0.1.0](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.0.0...v0.1.0) (2026-07-09)


### Bug Fixes

* **ws:** close Gate 4 audit gaps on US08.3.1 (PR [#28](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/28)) — rate-limit enforcement, oversized-payload handling, JavaDoc accuracy ([#36](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/36)) ([6e59737](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/6e59737526e7871f8fd992ad79cbe90138b5d089))


### Features

* **auth:** EN08.3 — real bearer-token auth cross-service (replaces fake header trust) ([#46](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/46)) ([2c1062a](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/2c1062a2a93a74c7b2e443b5947fb06ac678c75b)), closes [#45](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/45) [fr.pivot.auth.service.TokenService#validate](https://github.com/fr.pivot.auth.service.TokenService/issues/validate) [SimpMessagingTemplate#convertAndSendToUser](https://github.com/SimpMessagingTemplate/issues/convertAndSendToUser) [pivot-core#208](https://github.com/pivot-core/issues/208)
* **whiteboard:** board CRUD REST API — US08.1.1–US08.1.5 ([#19](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/19)) ([b13e34c](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/b13e34c5de8a6a5775b6699c0125e3babc6d7eb2)), closes [#18](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/18)
* **whiteboard:** join board via share token — US08.2.2 ([#23](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/23)) ([9ddb03f](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/9ddb03fc1295a96597945b93ced5f83e3cc508c8)), closes [#12](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/12)
* **whiteboard:** share token generation and revocation — US08.2.1 ([#21](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/21)) ([7283dd9](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/7283dd9d857aa1a12bf4d00e21b9afc43c4496f6)), closes [#20](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/20)
* **whiteboard:** US08.2.3 — gestion des membres (liste, rôles, suppression) ([#25](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/25)) ([7d48a24](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/7d48a242f47f2781c6f322e45c998e2e19e4b07b)), closes [#24](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/24)
* **whiteboard:** US08.3.1 — WebSocket canvas STOMP endpoint ([#28](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/28)) ([7217d8e](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/7217d8e66e82ac62fe4051833a828ca888912bbc))
* **whiteboard:** US08.4.1 — create board from template (backend) ([#31](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/31)) ([44e1b7c](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/44e1b7cfbe4ce84c36cc565bb88f65683c9447ed)), closes [#12](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/12) [#30](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/30)
* **ws:** EN07.3 — ActiveMQ STOMP broker relay for the collaboratif domain ([#35](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/35)) ([8538ff8](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/8538ff88f4d331b3a45bc2407cc223d845798c58)), closes [#34](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/34)
* **ws:** EN08.1 — WebSocket STOMP room isolation per board ([#27](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/27)) ([8528cda](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/8528cda354421620585df496a7f14ca334416b1f))
* **ws:** US08.5.1 — présence des participants, résolution collision [#32](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/32) ([#33](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/33)) ([9cb0d49](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/9cb0d496a3949282ea913ccacc7744edbf2990d7)), closes [#29](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/29)
