# PLAN — v2.2 "안정성 패리티 + 공개 배포 기반"

계획 문서. 구현은 승인 후 GPT-5.6-sol이 수행한다. 이 문서 외 어떤 파일도 아직 수정하지 않았다.

## Goal & done-criteria

목표: 공개 배포를 전제로, 1.21.4가 이미 가진 크래시 격리 보장을 26.2와 EventBus까지 확장하고, 버전 관리·CI 기반을 만든다. (기능 확장·유료 클라이언트 벤치마크는 이번 마일스톤 범위 밖 — Risks 참조.)

각 기준은 아래 명령/테스트로 독립 검증 가능해야 한다.

1. **G1 — Git 초기화**: `git rev-parse --is-inside-work-tree`가 성공하고, `.gitignore`가 반영된 초기 커밋이 1개 이상 존재한다 (`git log --oneline | wc -l` ≥ 1). `git status --short`에 `build/`, `run/`, `logs/`, `.gradle/`가 나타나지 않는다.
2. **G2 — CI 워크플로**: `.github/workflows/ci.yml`이 존재하고 `actionlint`(없으면 `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"`)를 통과한다. 워크플로는 `./gradlew :test :common:test :platform-26.2:test build`를 실행하도록 정의된다 (클라이언트 GameTest는 CI 범위 밖 — Risks R1).
3. **G3 — EventBus 리스너 격리**: 예외를 던지는 리스너가 있어도 (a) 이후 우선순위 리스너들이 모두 호출되고 (b) 예외가 `post()` 호출자에게 전파되지 않는다. 신규 단위 테스트가 이를 증명하며, 이 테스트는 현재 코드(`src/main/java/dev/b2tclient/event/EventBus.java:38-42`)에서는 실패해야 한다(회귀 증명). 부수 효과: `ClientRuntime.java:138/164`의 `ClientTickEvent` 리스너도 자동으로 보호된다.
4. **G4 — 26.2 틱 격리**: `ClientRuntime26.tick()`(`platform-26.2/.../v26/ClientRuntime26.java:266-308`)의 각 서브시스템 호출이 개별 가드된다. 한 서브시스템이 `RuntimeException`을 던져도 (a) 나머지 서브시스템이 같은 틱에 실행되고 (b) 원인 모듈이 자동 비활성화되며 (c) 로그에 모듈 id와 예외가 남는다. 신규 테스트로 증명.
5. **G5 — 26.2 `;b2t panic`**: `CommandManager26`에 `panic`이 추가되어 (a) `risk != PASSIVE`인 활성 모듈을 전부 비활성화하고 (b) `releasePlatformState` 경로(키 해제, 리스, 전투/이동, freecam, xray, Baritone)를 호출한다. 1.21.4 `CommandsAndServicesE2ETest`의 panic 검증(`src/gametest/.../CommandsAndServicesE2ETest.java:98`)과 동등한 테스트가 26.2에 존재하고 통과한다.
6. **G6 — 26.2 토글 되돌림**: `common/.../RegisteredModule.java:51-65`의 `setEnabled`/`toggle`이 실패 시 이전 상태로 되돌린다 (1.21.4 `core/Module.java:98-108`와 동일 계약). 신규 단위 테스트로 증명.
7. **G7 — 전체 게이트 회귀 없음**: `./gradlew clean qualityGate` 통과, `scripts/verify-release.sh` 통과(재현성 체크 포함), 기존 테스트 전부 그린.
8. **G8 — 버전/문서**: `gradle.properties`의 `mod_version=2.2.0`, `CHANGELOG.md` 머리에 기존 규칙(한국어, 주제별 소제목, 26.2 접두)대로 2.2.0 항목 존재. README/SECURITY의 panic 서술이 두 플랫폼 모두에서 사실이 된다.

## Files to touch

**신규**
- `.github/workflows/ci.yml` — 단위 테스트 + 빌드 CI (G2)
- `platform-26.2/src/test/.../ClientRuntime26ContainmentTest.java` (또는 기존 테스트 소스셋 규약에 맞는 위치) — G4
- `platform-26.2/src/gametest/.../e2e/` panic E2E 추가 또는 기존 스위트 확장 — G5
- `common/src/test/.../RegisteredModuleTest.java` 확장 또는 신규 — G6

**수정**
- `src/main/java/dev/b2tclient/event/EventBus.java` — `post()` 루프 본문 try/catch (log + continue) (G3)
- `src/test/java/dev/b2tclient/event/EventBusTest.java` — 던지는 리스너 케이스 추가 (G3)
- `platform-26.2/src/main/java/dev/b2tclient/v26/ClientRuntime26.java` — `tick()` 서브시스템별 가드 + 자동 비활성화 (G4)
- `platform-26.2/src/main/java/dev/b2tclient/v26/command/CommandManager26.java` — `panic` 서브커맨드 + 디스패치 가드 (G5)
- `common/src/main/java/dev/b2tclient/common/module/RegisteredModule.java` — 상태 되돌림 (G6)
- `gradle.properties` — `mod_version=2.2.0` (G8)
- `CHANGELOG.md` — 2.2.0 항목 (G8)
- `README.md`, `SECURITY.md` — panic 양 플랫폼 서술 갱신, CI 배지/절차 한 줄 (G8)

**액션(파일 아님)**: `git init` + 초기 커밋 (G1)

위 목록 밖의 파일은 건드리지 않는다. 예외가 필요하면 구현 전에 보고한다.

## Verification

순서대로, 각각 독립 실행:

1. `git rev-parse --is-inside-work-tree && git log --oneline | head -3 && git status --short` — G1 (출력에 build/run/logs 없음)
2. `actionlint .github/workflows/ci.yml` 또는 YAML 파스 — G2
3. `./gradlew :test --tests "dev.b2tclient.event.*"` — G3 (신규 케이스 포함 그린; 구현 전 해당 케이스만 레드였음을 커밋 이력으로 확인)
4. `./gradlew :platform-26.2:test` — G4, G6
5. `./gradlew :platform-26.2:runClientGameTest` — G5 (panic E2E 포함)
6. `./gradlew clean qualityGate` — G7 (양 플랫폼 check/build/E2E + verifyRelease26)
7. `scripts/verify-release.sh --repeat-builds 2` — G7 (SHA256SUMS 재현성 포함)
8. `grep "^mod_version=2.2.0" gradle.properties && head -5 CHANGELOG.md` — G8

## Risks / unknowns

- **R1 — CI에서 클라이언트 GameTest 미실행**: 헤드리스 러너에서 Fabric 클라이언트 GameTest(xvfb 등) 구동은 미검증. 이번 CI는 단위 테스트+빌드로 한정하고, `qualityGate`는 로컬 릴리스 게이트로 유지. GameTest CI 편입은 후속 과제.
- **R2 — 원격 저장소 부재**: `git init`은 로컬까지만. GitHub 푸시 전에는 ci.yml이 실제로 실행되지 않으므로 G2는 정적 검증(파스)까지만 보장.
- **R3 — 26.2 가드 단위의 모호함**: `ClientRuntime26`은 1654줄 모놀리스라 "서브시스템 → 원인 모듈" 매핑이 1:1이 아닌 곳(공유 상태를 만지는 combat/movement 경로)이 있다. 잘못 삼키면 상태 오염이 남을 수 있어, 가드는 격리+비활성화까지만 하고 상태 복구는 기존 `releasePlatformState` 부품을 재사용한다. 매핑이 불명확한 서브시스템은 구현 시 목록으로 보고.
- **R4 — 예외 캐치 범위**: `RuntimeException`만 캐치(1.21.4 `ModuleManager`와 동일 계약). `Error`류는 의도적으로 전파. Netty 스레드(`ConnectionMixin`) 경로에서 log+continue가 프로토콜 상태를 깨뜨리지 않는지 E2E로 확인 필요.
- **R5 — 재현성 게이트 회귀**: 소스 변경이 `verify-release.sh`의 SHA256SUMS 재현성 루프를 깨지 않아야 함(검증 7이 직접 확인).
- **R6 — "유료 클라이언트를 이긴다"는 이번 범위 밖**: 경쟁 클라이언트 대비 기능/성능 벤치마크는 정의 자체가 미확정(대상 클라이언트, 측정 항목). 안정성 패리티 완료 후 별도 마일스톤으로 제안 예정.
- **R7 — 26.2 panic의 risk 정보 접근**: 26.2에서 모듈별 `ModuleRisk` 조회가 공통 카탈로그(`BuiltinModuleCatalog`) 경유로 가능하다고 판단했으나, 카탈로그와 런타임 등록 간 불일치가 있으면 G5 구현이 커질 수 있음.
