# [0.3.0](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.2.1...v0.3.0) (2026-07-14)


### Bug Fixes

* **whiteboard:** le reset board diffuse "board:resetted", pas "RESET" ([#72](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/72)) ([c5b381b](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/c5b381b220e891df5c808a0aaf6ae395a14c94c4)), closes [WhiteboardBroadcastService#broadcastReset](https://github.com/WhiteboardBroadcastService/issues/broadcastReset) [#68](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/68) [#68](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/68)


### Features

* **whiteboard:** EN08.4 — modèle Card typé + contrats CARD_* (STOMP) ([#68](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/68)) ([0ed57ed](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/0ed57ed36d9a962eaae4c051bcbd2448abceadb4))

## [0.2.1](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.2.0...v0.2.1) (2026-07-14)


### Bug Fixes

* **api:** BoardResponse.role en majuscules, aligné sur MemberResponse/WS JOIN ([#70](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/70)) ([904657f](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/904657ff14ec214f715b1f36df8f6b9983414809))

# [0.2.0](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/compare/v0.1.2...v0.2.0) (2026-07-14)


### Bug Fixes

* **ci:** stop pushing mutable 'latest' Docker tag (forbidden by .plumber.yaml) ([#56](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/56)) ([b5b447b](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/b5b447b624ef3771a9d6f15f1270d784aeea19dd))
* **docker:** add HEALTHCHECK, curl, and separate actuator port ([#57](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/57)) ([9b2c042](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/9b2c0423ca0e81b36318f8d569e1031b71939a5d))


### Features

* **ci:** implement production deployment for real ([#60](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/60)) ([d9f793b](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/d9f793b724b86e3463a7cc7cd85d914f627b1566))
* **config:** wire production secrets via configtree (EN07.2 pattern) ([#61](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/61)) ([4bbb304](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/4bbb30420b43d9a77ead77af4a30822911aac9d3)), closes [#3](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/3)
* **whiteboard:** parité visible — favoris, corbeille, recherche, paramètres+reset (US08.1.6/7/8, US08.2.4) ([#66](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/66)) ([885d04f](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/commit/885d04ffd8fb5e9251742cc5a7ee0380f93f56a6)), closes [#65](https://github.com/PIVOT-PLATFORM/pivot-collaboratif-core/issues/65)

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
