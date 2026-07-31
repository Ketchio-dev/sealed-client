# Sealed Client 1.0.0

[CI workflow](.github/workflows/ci.yml)

Minecraft Java Edition 1.21.4와 26.2용으로 처음부터 작성한 Fabric 클라이언트입니다.
Nami를 비롯한 다른 클라이언트의 소스, 바이너리, 리소스는 포함하지 않습니다.

## 현재 지원 범위

| 대상 | Java | Loader / Fabric API | 구현 상태 |
| --- | --- | --- | --- |
| Minecraft 1.21.4 | 21 | 0.16.10 / 0.119.4+1.21.4 | 90개 모듈 전체 구현 |
| Minecraft 26.2 | 25 | 0.19.3 / 0.156.0+26.2 | 90개 모듈 전체 구현 |

두 버전은 별도 JAR로 배포하며 공통 90개 카탈로그를 모두 실제 플랫폼 동작에
연결합니다. 26.2에 마지막으로 포팅한 23개는 다음과 같습니다.

- Visual/World 13개: Player ESP, Tracers, Nametags, Storage ESP, Hole ESP,
  Block ESP, Trajectories, Freecam, XRay, Chams, New Chunks, Logout Spots,
  Stash Finder
- Utility/Baritone 8개: Auto Armor, Replenish, Chest Swap, Auto Mend,
  Fast Use, Inventory Manager, Auto Craft, Baritone Navigator
- HUD 2개: Tick Rate, Totem Pop (Local)

### 다른 버전은 어떻게 되나

지원 버전을 늘리는 비용을 추정하지 않고 측정했습니다. 1.21.4 소스를 그대로
1.21.8에 컴파일해 컴파일러가 센 값입니다.

| 시점 | 오류 | 영향 파일 |
| --- | --- | --- |
| 어댑터 도입 전 | 101 | 27 / 157 |
| 어댑터 도입 후 | 54 | 4 |

줄어든 47개는 전부 이름이 바뀐 것이었습니다. 핫바 슬롯, 이동 입력, 무기·곡괭이
판정, 저항 효과, 갑옷 슬롯, 순간이동이 그렇습니다. 이제 이런 접근은
`dev.sealedclient.platform`의 어댑터 4개(181줄)를 통해서만 이뤄지며, 소스 검사
테스트가 새 호출부의 우회를 막습니다.

남은 54개의 성격은 두 가지입니다.

- **44개 — 렌더 파이프라인.** `RenderSystem`의 상태 메서드가 삭제되고
  `RenderPipeline`으로 옮겨졌습니다. 이름 변경이 아니라 렌더 상태를 기술하는
  방식이 바뀐 것이라 실제 이식이 필요합니다. 26.2에서 같은 작업에 894줄이
  들었습니다. **1.21.4의 렌더 결과를 CI에서 검증할 수단이 없으므로**, 회귀를
  자동으로 잡지 못하는 상태에서 착수하지 않습니다.
- **9개 — 어댑터 파일 내부.** 이 숫자는 0이 될 수 없습니다. 결국 누군가는 실제
  API를 호출해야 하며, 그 지점이 3개 파일 9줄로 모였다는 것이 어댑터의 목적입니다.

따라서 1.0은 **1.21.4와 26.2 두 버전을 지원한다**고만 약속합니다. 다른 버전은
"곧 지원"이 아니라 위 표의 숫자만큼 남아 있는 상태입니다.

### 1.0이 약속하는 것

- 위 표의 두 버전에서 90개 모듈이 동작합니다.
- 설정 파일 형식과 명령 이름은 1.x 안에서 깨지 않습니다. 부득이하면 자동
  마이그레이션을 넣고 CHANGELOG에 적습니다.
- 측정으로 확인한 수치만 문서에 남깁니다. 확인하지 못한 설명은 가설이라고
  명시하며, 관용치에 조용히 흡수하지 않습니다.
- 외부 통신, 원격 측정, 런처 토큰 접근은 넣지 않습니다.

ViaFabricPlus와 Sodium은 설치 여부만 감지합니다. 1.21.4의 Baritone 연동은
별도로 설치한 공식 Baritone 1.13.1 API를 통해 좌표·웨이포인트 길찾기,
일시정지·재개·중지, 도착·정체·재시도 상태 확인을 제공합니다. Baritone은
Sealed Client JAR에 번들하거나 자동 다운로드하지 않습니다. 26.2 연동도 별도로 설치한
호환 Fabric provider와 필요한 Baritone API 표면이 있을 때만 활성화됩니다.
Sealed Client 프로젝트는 공식 Baritone의 Minecraft 26.2 stable 배포가 존재하거나
계속 제공된다고 보장하지 않습니다. 26.2 사용자는 provider의 실제 호환성과
배포 출처를 별도로 확인해야 합니다.

## “우리 앱인데 바이러스가 왜 문제인가?”

직접 만든 소스라도 실제로 실행하는 것은 빌드된 JAR과 그 의존성입니다. 따라서
비공식 미러나 Discord 첨부 파일로 JAR이 바뀌거나, 빌드 의존성 또는 빌드 환경이
오염되거나, 함께 설치한 애드온이 위험한 코드를 실행할 가능성은 별도로
확인해야 합니다.

Sealed Client는 이 위험을 줄이기 위해 다음 경계를 둡니다.

- 별도 HTTP 연결, 소켓, 웹훅, 텔레메트리, 원격 업데이트 없음
- Minecraft 런처 계정, 프로필, 인증 토큰 접근 없음
- 외부 프로세스 실행과 원격 코드 다운로드·로딩 없음
- 프로덕션 소스의 알려진 네트워크·프로세스 API 표식을 검사하는 정책 테스트
- Gradle 의존성 체크섬 고정
- 소스 JAR, SHA-256 목록, 로컬 생성 SBOM을 포함하는 감사 가능 배포 묶음

여기서 “로컬 전용”은 사용자가 선택한 Minecraft 서버와 게임 자체가 통신하지
않는다는 뜻이 아닙니다. Sealed Client가 Minecraft 연결과 별개인 은닉 통신 채널을
만들지 않는다는 뜻입니다. 정책 테스트, 체크섬, SBOM도 악성 코드가 절대로
없다는 보증은 아닙니다. 신뢰한 소스를 직접 검토하고 빌드하는 것이 가장 강한
검증 방법입니다. 자세한 설명은 [SECURITY.md](SECURITY.md)에 있습니다.

## 설치와 기본 조작

1. 사용할 Minecraft 버전에 맞는 Fabric Loader와 Fabric API를 설치합니다.
2. 같은 버전의 Sealed Client JAR 하나만 `mods` 폴더에 넣습니다.
   - 1.21.4: `sealed-client-mc1.21.4-1.0.0.jar`
   - 26.2: `sealed-client-mc26.2-1.0.0.jar`
3. 게임에서 `P`를 눌러 ClickGUI를 엽니다.

1.21.4 주요 조작:

- `P`: ClickGUI 열기/닫기
- `H`: HUD 편집기 열기/닫기
- `/` 또는 `Ctrl+F`: ClickGUI 모듈 검색
- 왼쪽 클릭: 모듈 전환 또는 다음 설정값
- 오른쪽 클릭: 설정 펼치기 또는 이전 설정값
- 가운데 클릭: 즐겨찾기 전환
- 마우스 휠: 목록 스크롤
- Key bind 행을 클릭한 뒤 키 입력: 모듈 단축키 지정
- 바인딩 입력 중 `Esc`: 단축키 제거

로컬 명령어는 `;sealed`로 시작하며 서버 채팅으로 전송되지 않습니다.
1.21.4 명령은 다음과 같습니다.

```text
;sealed help
;sealed modules [category]
;sealed toggle <module>
;sealed bind <module> <key|none>
;sealed set <module> <setting> <value>
;sealed profile list|create|use|delete|bind
;sealed friend list|add|remove
;sealed waypoint list|add|remove
;sealed baritone status|stop
;sealed baritone goto <x> [y] <z>
;sealed baritone goto waypoint <name>
;sealed config save|reload
;sealed panic
```

`panic`은 전투·이동·자동화 모듈을 끄고 Sealed Client가 점유한 입력과 슬롯
동작 및 Sealed Client가 시작한 Baritone 경로를 해제합니다.

Baritone을 사용하려면 공식 1.21.4 `baritone-api-fabric` JAR을 Sealed Client와 별도로
`mods` 폴더에 설치해야 합니다. API 클래스를 제거한 `standalone-fabric`
변형은 Sealed Client 연동용이 아닙니다. GUI의 `Baritone Navigator`는
`AUTOMATION` 위험도로 기본 비활성화되며, 좌표를 설정한 뒤
`Confirm Target`을 켜야 한 번 시작됩니다. Sealed Client를 Baritone 없이 설치해도
정상 부팅하며 관련 명령과 모듈은 안전하게 실패합니다. 26.2에서는 별도
provider가 Fabric 모드로 로드되고 필요한 API가 호환될 때만 연동합니다.

ClickGUI 왼쪽의 `Presets`에서 다음 내장 프리셋을 미리 보고 적용할 수 있습니다.

- `Low Lag Utility`: 수동·정보 기능 중심의 저부하 구성
- `Travel Safe`: 이동 중 안전·상태 확인 중심의 보수적 구성
- `Crystal Practice`: 사설 연습 월드용 전투 설정

안전 적용은 `PASSIVE` 모듈만 켜고 더 높은 위험도 모듈의 설정값만 준비합니다.
위험 기능까지 켜려면 5초 안에 한 번 더 확인해야 하며, 적용 도중 실패하면 전체
변경을 되돌립니다. 마지막 프리셋 적용은 `Undo`로 한 번 취소할 수 있습니다.

26.2는 `P` GUI와 다음 명령을 제공합니다.

```text
;sealed help
;sealed list [category]
;sealed status <module-id>
;sealed toggle <module-id>
;sealed friend add|remove|list [name]
;sealed waypoint add|remove|list [name]
;sealed profile list
;sealed profile save <name> [server-pattern]
;sealed profile use <name>
;sealed profile delete <name>
;sealed profile gui
;sealed hud edit|reset
;sealed baritone status|stop
;sealed baritone goto <x> <y> <z>
;sealed panic
```

두 플랫폼의 `panic`은 활성 비수동 모듈을 모두 끄고 Sealed Client가 점유한 키·슬롯,
전투·이동, Freecam·XRay 및 Sealed Client가 시작한 Baritone 경로를 해제합니다.

해당 어댑터의 GUI는 카테고리·검색·즐겨찾기·위험도 표시와
Boolean/Integer/Double/String 설정 편집을 지원합니다. 90개 카탈로그 항목은
모두 26.2 구현에 연결되어 있습니다.

`P` GUI 안에서 다음 화면으로 이동합니다.

| 키 | 화면 |
| --- | --- |
| `H` | HUD 편집기 (패널 드래그, `R` 초기화) |
| `O` | 프로필 관리 (활성 표시·서버 패턴·저장·사용·삭제) |
| `K` | 프리셋 (미리보기, 위험 확인, 롤백, `U` 실행 취소) |

모듈별 키바인딩은 설정 패널 상단의 `Keybind` 행에서 편집합니다. 행을 클릭한
뒤 원하는 키를 누르면 바인딩되고, `Escape` 또는 가운데 클릭으로 해제합니다.
같은 키를 여러 모듈에 바인딩하면 함께 토글되며 상태줄에 함께 토글되는 모듈을
표시합니다. 수식 키 단독 바인딩은 거부합니다. 바인딩된 키는 화면이 열려 있지
않을 때만 동작하므로 채팅이나 설정 입력이 모듈을 토글하지 않습니다.

HUD 편집기는 `Info`와 `Module list` 패널을 드래그로 배치하며, 위치는 화면
비율로 저장합니다. 해상도나 GUI 스케일이 바뀌어도 패널이 화면 밖으로 잘리지
않도록 매 프레임 다시 제한하고, 패널이 화면보다 큰 경우에는 좌상단에
고정합니다.

XRay와 Block ESP의 블록 목록에 인식할 수 없는 값을 입력하면 설정 항목 아래에
`Invalid id` 또는 `No such block`으로 구분해 표시하고 실제로 적용된 개수를
함께 보여줍니다.

Target HUD는 크로스헤어가 아니라 KillAura/TriggerBot/AutoCrystal이 실제로
선택한 대상을 표시하며, 어떤 모듈이 선택했는지 `(Aura)`, `(Trigger)`,
`(Crystal)`로 구분합니다. 전투 모듈이 아무것도 선택하지 않은 경우에만
`(Crosshair)`로 대체합니다.

26.2에서 프로필을 저장할 때 `server-pattern`을 생략하면 현재 서버 주소에
연결합니다. `*`와 `?`를 포함한 제한형 패턴도 사용할 수 있으며, 접속하거나
재접속할 때 가장 구체적으로 일치하는 프로필을 자동 적용합니다.

## 기능 개요

- HUD: 좌표, 방향, 속도, FPS, 핑, 체력, 방어구, 보급품, 레이더, 세션,
  서버/TPS 정보, 대상 정보, 토템 사용 추정과 드래그 가능한 HUD 편집기
- Combat: 방어·저항·노출·자해·페이스플레이스·예측을 반영하는 Auto Crystal,
  Auto Totem/Mine, Offhand, Kill Aura, Surround, Hole Fill, Self/Auto Trap,
  Burrow, Anchor/Bed Aura 등
- Visual/World: Player/Storage/Block/Hole ESP, Tracers, Nametags,
  Trajectories, Waypoints, New Chunks, Logout Spots, Freecam, XRay, Chams,
  Stash Finder, Portal Coords 등
- Movement: 컨텍스트 워밍업, 고핑 감속/일시정지, 서버 위치 보정과 순간이동
  감지가 적용된 Auto Walk/Center, Hole Snap, Step, No Fall, Fast Swim,
  Jesus, Elytra Swap/Control, Ground Speed, Safe Walk, No Slow, No Rotate 등
- Utility: Auto Eat/Armor/Tool/Mend/Respawn/Reconnect, Replenish,
  Inventory Manager, Chest Swap, Anti AFK, Fast Use, Auto Craft,
  선택적 Baritone Navigator 등
- 기반 기능: 이벤트 버스, 패킷 이벤트, 액션 충돌 조정, 친구 제외,
  서버별 프로필, 웨이포인트, 알림, 로컬 Fabric 애드온 진입점

`New Chunks`는 기준선 스캔을 마친 뒤 **현재 클라이언트 세션에서 처음
관측한 청크**를 표시합니다. 서버가 해당 청크를 최근에 새로 생성했다는
증거가 아니며, 청크 생성 시점이나 다른 플레이어의 선행 방문 여부를 판정하지
않습니다.

전투·이동·인벤토리 자동화 기능은 기본적으로 비활성화되며 위험도 라벨을
표시합니다. 친구는 지원되는 공격·타게팅 모듈에서 기본 제외됩니다.

## 설정과 복구

설정은 Minecraft 폴더의 `config/sealedclient/config.json`에 저장됩니다. v2
포맷은 모듈 설정, 즐겨찾기, 프로필, 서버별 프로필 연결, 친구와 차원별
웨이포인트를 저장합니다. 기존 v1 설정은 기본 프로필로 자동 이관됩니다.

정상적으로 읽은 설정은 `config.json.bak`으로 백업됩니다. JSON이 손상되면
원본은 `config.corrupt-날짜.json`으로 보존하고 백업 또는 안전한 기본값으로
복구합니다. 파일 시스템이 지원하면 임시 파일과 원자적 교체를 사용합니다.

26.2 설정은 `config/sealedclient-26.2.json`에 별도로 저장됩니다. 명시적 schema
v1, 1 MiB 크기 제한, 임시 파일+원자적 교체와 손상 파일 격리를 적용합니다.
접속 해제와 종료 때 Sealed Client가 누른 이동 키, 감마와 View Bob 상태도 복구합니다.

## 빌드와 테스트

Gradle toolchain이 1.21.4용 Java 21과 26.2용 Java 25를 사용합니다.

```shell
java -version
./gradlew --no-daemon clean :common:clean :platform-26.2:clean multiVersionBuild
./gradlew --no-daemon e2eTest
```

전체 품질 게이트:

```shell
./gradlew --no-daemon clean qualityGate
```

GitHub Actions CI도 `.github/workflows/ci.yml`에서 양 플랫폼 단위 테스트와
`build`를 실행합니다. Loom이 운영체제별로 생성하는 매핑 JAR 때문에 공개 CI의
해당 명령만 Gradle 검증을 `lenient`로 실행하며, 고정된 외부 의존성 메타데이터와
로컬 릴리스 게이트는 계속 strict 검증을 사용합니다. Client GameTest와 재현성 검사는 공개 CI 범위 밖이므로
릴리스 전에는 위 로컬 품질 게이트와 `scripts/verify-release.sh --repeat-builds 2`를
별도로 실행해야 합니다.

연결이 예고 없이 끊기는 상황의 정리·재접속 검증. 실제 네트워크 경로를
실행하려면 EULA 동의가 필요하며, 생략하면 스위트가 건너뛰기만 확인합니다.
GameTest는 공개 CI 범위 밖이므로 이 게이트는 로컬에서만 보증됩니다:

```shell
./gradlew :platform-26.2:networkResilienceTest -Psealed.minecraftEula=true
```

성능 불변조건 테스트를 기본 3회 반복:

```shell
scripts/performance-soak.sh
```

전투·이동·Baritone 부재 경로·프리셋·26.2 카탈로그를 한 번에 확인하는 경쟁력
통합 게이트:

```shell
scripts/competitive-integration-gate.sh
```

공식 1.21.4 Baritone API Fabric 모드를 임시로 내려받아 공식 체크섬을
확인하고 실제 설치 상태의 전체 Client GameTest를 실행:

```shell
scripts/baritone-integration-smoke.sh
```

이 스크립트만 명시적으로 외부 릴리스 파일을 내려받습니다. Loom이 로컬 모드를
개발 매핑으로 다시 만들기 때문에 생성된 임시 파일의 의존성 검증은
`lenient`로 실행하지만, 입력 JAR은 실행 전에 공식 릴리스 체크섬과 대조합니다.

검증 수준은 다음처럼 구분합니다.

| 수준 | 확인 범위 | 포함하지 않는 범위 |
| --- | --- | --- |
| 공통·26.2 자동 테스트 | 90/90 카탈로그, 설정·상태 머신, 렌더·월드 탐지 결정 로직, 액션 중재, Baritone 부재·비호환 경로, JAR·SBOM·체크섬 | 실제 GPU 렌더링, 실제 외부 서버 지연 |
| 1.21.4 Client GameTest | 격리된 싱글플레이/통합 서버의 부팅, GUI·HUD, 설정 복구, Freecam/XRay와 저장소·포털 탐지 | 2b2t 접속 |
| 26.2 Client GameTest (싱글플레이) | 실제 창에서 90/90 카탈로그, ClickGUI·HUD 편집기·프로필·프리셋 화면, 키바인딩 토글, 작은 화면 클리핑 방지 | 멀티플레이 네트워크 경로 |
| 26.2 Client GameTest (전용 서버) | **실제 TCP 소켓** 접속. 서버 주소 기반 프로필 자동 적용, 재접속 시 스냅샷 재적용, 실제 수신 패킷 기반 TPS 추정, 접속 해제 정리, 전 모듈 활성 상태의 제한 soak | 2b2t 대기열·안티치트·실제 인구 |
| 26.2 Client GameTest (고핑·저TPS) | 지연 프록시를 통한 실측 왕복 시간 비교, `/tick rate`로 강제한 실제 저TPS 감지와 회복, 지연과 저TPS의 구분 | 변동하는 실제 네트워크, 다시간 지속 |
| 26.2 그래픽 부팅 스모크 | `:platform-26.2:runClient`로 실제 게임 창, 메인 메뉴와 `P` ClickGUI 부팅 확인 | 청크 렌더 경로 (전용 서버 E2E가 담당) |

1.21.4 E2E 로그는 `build/run/clientGameTest/logs/latest.log`, 스크린샷은
`build/run/clientGameTest/screenshots/`에 생성됩니다. 26.2 그래픽 부팅은
자동 단위 테스트와 별개의 수동 스모크이므로 배포 JAR도 별도 테스트
인스턴스에서 먼저 확인하십시오.

```shell
./gradlew --no-daemon :platform-26.2:runClient
```

메인 메뉴까지의 부팅 스모크는 청크 렌더 경로를 전혀 타지 않습니다.
`SectionCompiler`는 월드가 청크를 그리기 전까지 로드되지 않으므로, 렌더 관련
Mixin을 수정했다면 반드시 26.2 Client GameTest를 실행해야 합니다.

### 26.2 전용 서버·고핑 E2E

전용 서버를 띄우는 검증은 Mojang EULA 동의가 필요합니다. 동의는 빌드를
실행하는 사람의 결정이므로 자동으로 기록하지 않으며, 플래그가 없으면 해당
스위트는 실패가 아니라 건너뜁니다.

```shell
./gradlew --no-daemon -Psealed.minecraftEula=true :platform-26.2:runClientGameTest
```

이 스위트는 실제 TCP 소켓으로 로컬 전용 서버에 접속해 다음을 확인합니다.

- 서버 주소를 키로 한 프로필 자동 적용과 재접속 시 스냅샷 재적용
  (싱글플레이 통합 서버는 `singleplayer`를 반환하므로 이 경로를 검증할 수 없습니다)
- 실제 수신 패킷에서 계산한 서버 TPS 추정과 접속 해제 시 초기화
- 지연 프록시(`LatencyProxy26`)를 경유했을 때의 실측 왕복 시간 증가
- `/tick rate`로 강제한 실제 저TPS 감지, 그리고 **지연과 저TPS의 구분**
  (멀기만 한 서버를 느린 서버로 오인하지 않는지)

여전히 재현하지 않는 것: 2b2t의 대기열, 안티치트, 실제 인구, 변동하는
네트워크 품질, 그리고 다시간 지속 soak. 장거리 Elytra/Baritone 이동도
자동 검증 대상이 아닙니다. 따라서 테스트 통과를 실서버 장시간 안정성
보증으로 해석하면 안 됩니다.

## 측정된 상한

전투 성능을 "빠르다"거나 "정확하다"로 주장하는 대신, 물리적·수학적으로 더
나아질 수 없는 지점을 정하고 거기에 도달했는지를 측정합니다. 아래 수치는
전용 서버에 실제로 붙어 실제 폭발을 터뜨려 얻은 것입니다.

| 항목 | 이론적 상한 | 측정값 | 왜 그 이상이 불가능한가 |
| --- | --- | --- | --- |
| 반응 지연 | 1틱 | 1틱 (12/12 샘플) | 클라이언트는 패킷을 읽은 뒤 틱을 돌리고, 그 틱 끝에 행동을 보냅니다. 원인을 관측한 틱보다 먼저 반응을 보낼 수는 없습니다 |
| 폭발 데미지 예측 | 오차 0 | 10개 중 7개가 오차 0, 최대 0.500 | 아래 설명 참조 |
| 크리스탈 선택 | 최적해 | 전수 탐색과 일치 (2000회 무작위 시행) | 후보 집합에서 점수가 가장 높은 것을 고르는 것이 정의상 최선입니다 |

재현:

```shell
./gradlew :platform-26.2:combatAccuracyTest -Psealed.minecraftEula=true
```

이 게이트는 Client GameTest라 공개 CI 범위 밖이며 로컬에서만 보증됩니다.
대신 실측으로 검증된 공식과 그 시나리오 표는 단위 테스트로 고정되어 매
빌드에서 검사됩니다.

**남은 오차 0.500에 대하여.** 10개 시나리오 중 7개는 서버가 적용한 피해와
소수점까지 일치합니다. 일치하지 않는 3개는 모두 무방비 상태의 근거리 피격이며
0.500, 0.167, 0.167만큼 낮게 예측합니다.

이 잔차가 무엇이 아닌지는 계측으로 확인했습니다. 거리나 시야 판정 때문이
아닙니다 — 게임 테스트가 서버 자신의 좌표와 블록으로 같은 계산을 돌려도 예측값이
동일합니다. 낙하 피해나 흡수 하트도 아닙니다. 측정해서 배제했습니다. 실행을
반복해도 값이 같으므로 타이밍 문제도 아닙니다.

남은 가설은 서버가 감쇄 계산을 `float`로 누적하는 반면 우리는 `double`로
계산한다는 것입니다. 이 가설은 오차가 나타나는 위치와 맞아떨어집니다. 방어구
경로를 타는 경우는 모두 정확하고, 그 경로를 건너뛰는 큰 무방비 피해에서만
어긋납니다. 다만 이는 아직 가설이며 확정된 사실이 아니라서, 허용 오차 안에
슬쩍 묻지 않고 이렇게 남겨둡니다.

오차의 방향은 한쪽입니다. 예측값이 실제 피해보다 낮은 경우는 없습니다. 자기
피해 판정에서는 과대평가가 안전한 방향입니다.

**이 수치가 말하지 않는 것.** 다른 클라이언트를 벤치마크한 결과가 아닙니다.
비교 대상 없이 절대적 상한 대비 우리 위치만을 측정했습니다. 또한 클라이언트는
서버가 보는 모든 정보를 볼 수 없으므로(상대 방어구의 인챈트 등), 예측 정확도의
상한은 "오차 0"이 아니라 "클라이언트가 볼 수 있는 정보로 도달 가능한 최소
오차"입니다.

## 배포 묶음과 SHA-256

```shell
scripts/verify-release.sh
```

`build/multiversion-release/`에는 다음 파일이 생성됩니다.

- `sealed-client-mc1.21.4-1.0.0.jar`
- `sealed-client-mc1.21.4-1.0.0-sources.jar`
- `sealed-client-mc26.2-1.0.0.jar`
- `sealed-client-mc26.2-1.0.0-sources.jar`
- `sealed-client-1.0.0.sbom.json`
- `sealed-client-26.2-1.0.0-bom.json`
- 통합 `SHA256SUMS`와 플랫폼별 체크섬 목록
- `SECURITY.md`, `NOTICE`

SHA-256은 파일이 게시 후 바뀌었는지를 확인합니다. 단, JAR과 해시를 같은
신뢰할 수 없는 곳에서 받으면 둘 다 바뀔 수 있으므로 공식적으로 검토한
소스나 별도의 신뢰 경로에서 해시를 확인해야 합니다. SBOM은 런타임 의존성
목록과 그 해시를 기록하는 감사 자료이며, 취약점 검사 결과나 안전 인증서가
아닙니다.

이미 생성된 묶음만 다시 검사하려면
`scripts/verify-release.sh --skip-build`를 사용합니다.

## 재현 빌드 상태

JAR 작업은 파일 타임스탬프를 제거하고 파일 순서를 고정합니다. 이는 같은
입력에서 같은 바이트가 나올 가능성을 높이지만, 프로젝트가 서로 다른
컴퓨터·JDK에서의 완전한 재현성을 자동 인증하지는 않습니다.

현재 환경에서 두 번 생성한 전체 릴리스 체크섬을 비교하려면:

```shell
scripts/verify-release.sh --repeat-builds 2
```

스크립트는 각 빌드의 `SHA256SUMS`와 SBOM 구조도 확인합니다. 성공은 이
환경에서 반복 생성한 배포 파일들의 체크섬이 같다는 뜻입니다. 이것만으로
소스, 빌드 도구 또는 빌드 머신이 안전하다고 증명되지는 않습니다.

## 서버 사용 책임

이 프로젝트는 자동 채팅, 광고·스팸, 패킷 폭주, 서버 크래시, 계정 탈취
기능을 포함하지 않습니다. 서버 규칙과 허용 범위는 바뀔 수 있으며 사용자는
접속하는 서버의 최신 규칙을 직접 확인해야 합니다. 위험도가 높은 기능은
사설 테스트 월드/서버에서 먼저 검증하십시오.


## 라이선스

Sealed Client는 [Apache License 2.0](LICENSE)으로 배포됩니다. 배포 묶음에는
`LICENSE`와 Apache NOTICE 규약에 따른 `NOTICE`가 포함됩니다. Minecraft, Fabric,
Baritone 등 서드파티 구성 요소는 각각의 라이선스를 따릅니다.
