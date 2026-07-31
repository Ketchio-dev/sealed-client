# Changelog

## 3.1.0

### 회전 아비트레이션

- 양 플랫폼이 공유하는 `RotationController`를 `common`에 추가해 한 틱에
  여러 모듈이 조준을 요청해도 우선순위가 가장 높은 하나만 반영되도록 했습니다.
  우선순위가 같으면 먼저 요청한 쪽을 유지합니다.
- 조준 각도를 실제로 쓰는 지점을 플랫폼마다 `RotationApplier` 하나로 모았고,
  기존에 직접 `setYRot`/`setXRot`를 호출하던 14개 지점을 모두 전환했습니다.
  정책 테스트가 직접 호출의 재발을 빌드 실패로 막습니다.
- 26.2는 그동안 조준 조정 장치가 아예 없어 같은 틱에 공성·활·전통·엘리트라·
  자동 수리가 서로의 조준을 덮어썼습니다. 이번에 1.21.4와 동등한 조정 체계를
  갖췄습니다.
- 틱당 최대 회전량을 제한하는 보간을 추가했습니다. 기본값은 180도로 기존
  동작과 동일하며, 낮추면 여러 틱에 걸쳐 목표 각도로 이동합니다. yaw는
  -180/180 경계를 최단 경로로 넘어갑니다.
- 아무 모듈도 조준하지 않는 틱에는 개입 이전 각도로 되돌립니다. 사용자가
  마우스를 움직였거나 서버가 시점을 교정한 경우에는 되돌리지 않습니다.

### 네트워크 단절 검증

- `LatencyProxy26`에 진행 중인 연결만 끊고 리스너는 유지하는 기능을 더해,
  연결 종료 패킷 없이 회선이 사라지는 상황을 재현합니다.
- 26.2 Client GameTest에 단절·정리·재접속 시나리오를 추가했습니다. 클라이언트가
  단절을 인지하고 상태를 해제한 뒤 같은 주소로 재접속하며, 이전 세션의 상태가
  남지 않는지 확인합니다.
- `networkResilienceTest` 태스크로 분리했습니다. 실제 네트워크 경로를
  검증하려면 `-Psealed.minecraftEula=true`가 필요합니다.

### 기타

- 서버 사이드바의 대기열 표시를 읽는 규칙을 `QueueTextParser`로 분리해 실제
  스코어보드 없이 단위 테스트로 검증합니다.
- 테스트 코드에 남아 있던 리브랜드 이전 스레드 이름과 서버 라벨을 정리했습니다.

## 3.0.0

### Sealed Client 리브랜드

- 제품명, Java 패키지, mod id, Maven group과 배포 파일명을 Sealed Client로
  통일했습니다.
- 로컬 명령 프리픽스를 `;sealed`로, 설정 경로를 `config/sealedclient/`와
  `sealedclient-26.2.json`으로, 휴대용 프로필 포맷을 `sealed-profile`로
  변경했습니다.
- 공개 배포 전 클린 브레이크로 적용했으며 이전 식별자용 마이그레이션 shim은
  제공하지 않습니다.

### 라이선스와 공개 배포

- 프로젝트 라이선스를 표준 Apache License 2.0으로 전환하고 LICENSE와 NOTICE를
  양 플랫폼 배포 묶음에 포함했습니다.
- GitHub 공개 저장소와 실제 CI 검증을 위한 패키지·GameTest·스크립트·보안 정책
  경로를 새 아이덴티티에 맞게 갱신했습니다.

### 릴리스 검증

- 양 플랫폼 JAR·소스 JAR·SBOM 이름을 3.0.0 규칙으로 통일하고 반복 빌드
  SHA-256 재현성 게이트를 유지했습니다.
- 새 명령 프리픽스의 panic/list 처리와 이전 프리픽스 미인식을 Client
  GameTest로 검증합니다.

## 2.2.0

### 안정성 격리

- 이벤트 리스너 하나가 예외를 던져도 이후 우선순위 리스너를 계속 호출하고,
  예외를 이벤트 발행자에게 전파하지 않도록 격리했습니다.
- 26.2 틱 서브시스템을 개별 가드해 런타임 예외가 난 모듈을 자동으로 끄고
  모듈 id와 예외를 기록한 뒤 같은 틱의 나머지 서브시스템을 계속 실행합니다.
- 26.2 모듈 상태 전환이 실패하면 이전 활성 상태로 되돌리도록 계약과 테스트를
  추가했습니다.

### 26.2 안전 제어

- 26.2에 `;b2t panic`을 추가해 활성 비수동 모듈을 모두 끄고, B2T가 점유한
  키·슬롯·전투·이동·Freecam·XRay·Baritone 상태를 즉시 해제합니다.
- 26.2 로컬 명령 디스패치를 격리해 명령 실패를 로그에 남기고 채팅 처리 경계
  밖으로 전파하지 않도록 했습니다.

### 공개 배포 기반

- GitHub Actions에서 양 플랫폼 단위 테스트와 전체 빌드를 실행하는 CI를
  추가하고 공개 배포 버전을 2.2.0으로 올렸습니다.
- 이벤트 격리, 26.2 틱 격리, panic 및 상태 되돌림 회귀 테스트를 추가했습니다.

## 2.1.0

### 실전 로직과 이동 안정성

- Auto Crystal에 난이도 기반 폭발 피해, 노출도, 방어구·강인함·저항·보호
  마법부여 감소, 자해 가중치, 페이스플레이스, 짧은 예측과 제한된 후보 점수를
  추가했습니다.
- Auto Crystal 설치·파괴에 서버 생성·제거 패킷 확인, 제한된 재시도와
  지수형 백오프, 실패 냉각 및 세션 초기화를 추가했습니다.
- Crystal/Anchor/Bed 자동화에 친구 보호, 비치명 자해 한도, 슬롯 복원과
  독립적인 설치·파괴 지연을 적용했습니다.
- 이동 모듈 공통 안전 제어기에 컨텍스트 워밍업, 고핑 감속/일시정지,
  서버 위치 보정 패킷 반복, 지연 급변·수신 정체와 순간이동 감지, 안정화
  히스테리시스 및 접속 해제 시 상태 해제를 추가했습니다.

### Baritone, 프리셋과 GUI

- 별도로 설치한 공식 Baritone 1.13.1과 연동하는 좌표·웨이포인트 이동,
  일시정지·재개·중지, 도착·정체·시간 초과·제한 재시도 상태와
  `Baritone Navigator` 모듈을 추가했습니다.
  Baritone은 B2T JAR에 포함하거나 자동 다운로드하지 않습니다.
- 26.2에서는 별도 설치한 호환 provider를 런타임에 확인해
  `;b2t baritone goto <x> <y> <z>|stop|status`와 Navigator를 연결합니다.
  공식 Baritone의 26.2 stable 배포는 B2T가 보장하지 않으며, provider가
  없거나 API가 맞지 않으면 아무 경로도 시작하지 않고 안전하게 비활성화됩니다.
- `Low Lag Utility`, `Travel Safe`, `Crystal Practice` 프리셋과 변경 미리보기,
  위험 기능 2단계 확인, 트랜잭션 롤백과 한 단계 실행 취소를 ClickGUI에
  추가했습니다.
- 활성 프로필을 클립보드로 내보내고 가져올 수 있으며, 친구·웨이포인트는
  제외하고 위험 모듈 활성화는 별도 확인하도록 제한했습니다.

### 26.2 포팅과 검증

- Minecraft 26.2의 공통 90개 카탈로그를 90개 실제 구현에 모두 연결했습니다.
  마지막 23개는 Visual/World의 Player ESP, Tracers, Nametags,
  Storage/Hole/Block ESP, Trajectories, Freecam, XRay, Chams, New Chunks,
  Logout Spots, Stash Finder, Utility의 Auto Armor, Replenish, Chest Swap,
  Auto Mend, Fast Use, Inventory Manager, Auto Craft, Baritone Navigator,
  HUD의 Tick Rate와 Totem Pop (Local)입니다.
- 26.2 명령에 모듈 목록·상태·토글, 친구, 웨이포인트, 프로필과
  `baritone goto|stop|status`를 연결했습니다.
- 26.2 프로필에 제한형 `*`/`?` 서버 주소 패턴과 접속·재접속 시 가장
  구체적인 일치 항목의 자동 적용을 추가했습니다.
- New Chunks는 기준선 이후 현재 클라이언트 세션에서 처음 관측한 청크만
  표시합니다. 서버가 최근 생성한 청크라는 증거로 취급하지 않습니다.
- 26.2 전투 자동화에 원자적 액션 중재, 친구 제외, 체력·자폭·친구 피해
  안전 한도, 서버 월드 상태 확인, 제한된 재시도와 정확한 슬롯 복구를
  적용했습니다.
- 방어 건축·차원 폭발·공성은 단계별 서버 반영 확인과 제한된 실패 냉각을,
  Bow Aim은 탄도·FOV·회전 속도·수동 입력 양보를, Quiver는 유익한 효과와
  탄약·내구도·효과 적용 확인을 사용합니다.
- 26.2 GUI에 카테고리, 검색, 즐겨찾기, 위험도, 모듈별 범위·체력·피해·쿨다운
  설정 편집과 입력 범위 검증을 추가했습니다.
- 전투 피해·후보 선택, 이동 보정/핑 정책, Baritone 부재 경로, 프리셋 원자성,
  플랫폼 카탈로그를 검증하는 결정론적 테스트를 추가했습니다.
- 새 경쟁력 통합 게이트에서 반복 토글 뒤 액션 점유 해제와 전체 플랫폼 검증을
  함께 수행합니다.
- 자동 테스트와 별도로 26.2 실제 그래픽 창의 메인 메뉴 부팅 스모크를
  통과했습니다. 실제 2b2t 고핑·저TPS, 다시간 접속과 장거리
  Elytra/Baritone 이동을 결합한 soak는 아직 별도 실서버 검증 과제입니다.

### 26.2 GUI, HUD와 키바인딩

- 26.2 모듈의 `keyCode`를 런타임에서 실제로 소비합니다. GLFW 키 상태를 틱마다
  폴링해 눌리는 순간에만 토글하므로 키를 누르고 있어도 반복 토글되지 않고,
  화면이 열려 있는 동안에는 입력을 삼켜 채팅·설정 입력이 모듈을 토글하지
  않습니다. 접속 해제 시 눌린 키 상태를 초기화합니다.
- ClickGUI 설정 패널에 모듈별 `Keybind` 편집 행을 추가했습니다. 수식 키 단독
  바인딩은 거부하고, 같은 키를 공유하는 다른 모듈을 상태줄에 표시합니다.
  범위를 벗어난 값이 설정 파일에 있어도 해제 상태로 정규화합니다.
- 드래그 가능한 26.2 HUD 편집기(`H`)와 `Info`/`Module list` 패널을
  추가했습니다. 위치는 화면 비율로 저장하고 매 프레임 화면 안으로 다시
  제한하므로 해상도나 GUI 스케일이 바뀌어도 잘리지 않으며, 패널이 화면보다
  크면 좌상단에 고정합니다. `;b2t hud edit|reset`로도 접근합니다.
- 26.2 내장 프리셋 화면(`K`)에 변경 미리보기, 위험도 기반 2단계 확인,
  검증 실패 시 전체 롤백, 한 단계 실행 취소(`U`)를 추가했습니다.
  프로필을 전환하면 실행 취소 기준선을 폐기합니다.
- 26.2 프로필 관리 화면(`O`)에 활성 표시, 서버 패턴, 저장·사용·삭제를
  추가했습니다. 삭제는 2단계 확인이며 마지막 프로필은 삭제하지 않습니다.
  `;b2t profile delete|gui`도 추가했습니다.
- Target HUD를 크로스헤어가 아니라 KillAura/TriggerBot/AutoCrystal이 실제로
  선택한 대상에 연결하고 선택 모듈을 함께 표시합니다. 전투 모듈이 아무것도
  선택하지 않았을 때만 크로스헤어로 대체하며, 접속 해제 시 초기화합니다.
- XRay/Block ESP 블록 목록의 인식 불가 항목을 GUI에 `Invalid id`와
  `No such block`으로 구분해 표시하고 실제 적용 개수를 함께 보여줍니다.
- Freecam 사용 중에는 Block/Hole ESP 스캔이 플레이어가 아니라 카메라 위치를
  기준으로 동작합니다.
- Chams의 RenderType 캐시를 리소스 리로드 시에도 정리합니다.
- 접속 해제 시 이전 서버의 사망 위치 표시를 초기화합니다.

### 26.2 검증

- `FastUseCooldownAccess26`의 reflection을 Mixin accessor로 대체해 26.2
  production 코드의 reflection을 선택적 Baritone 어댑터 한 곳으로 되돌렸습니다.
- 패키지 선언과 다른 디렉터리에 있던 `AvatarRendererNametagMixin26`을
  올바른 위치로 옮겼습니다.
- 26.2 전용 서버 E2E를 추가했습니다. 실제 TCP 소켓으로 로컬 전용 서버에
  접속해, 싱글플레이 통합 서버로는 검증할 수 없던 멀티플레이 경로를
  확인합니다. 서버 주소를 키로 한 프로필 자동 적용, 재접속 시 스냅샷
  재적용, 실제 수신 패킷 기반 서버 TPS 추정과 접속 해제 시 초기화,
  전 모듈을 켠 상태의 제한 soak를 포함합니다.
- 26.2 고핑·저TPS E2E를 추가했습니다. 테스트 전용 지연 프록시
  (`LatencyProxy26`)를 클라이언트와 서버 사이에 두고 실측 왕복 시간을
  직접 접속 시와 비교하며, `/tick rate`로 서버를 실제로 느리게 만들어
  저TPS 감지와 회복을 확인합니다. 특히 **단순히 먼 서버를 느린 서버로
  오인하지 않는지**를 함께 검증합니다.
- 전용 서버 기동은 Mojang EULA 동의가 필요하므로 자동으로 기록하지 않습니다.
  `-Pb2t.minecraftEula=true` 없이 실행하면 해당 스위트는 실패가 아니라
  건너뜁니다.
- 26.2용 Fabric client gametest E2E를 추가했습니다. 이 E2E가 XRay Mixin의
  `tesselateBlock` `@Redirect`가 fabric-renderer-api-v1의 같은 호출 지점과
  충돌해 월드 렌더링 시 `SectionCompiler` Mixin 적용이 실패하던 문제를
  실제로 잡아냈습니다. 해당 주입을 MixinExtras `@WrapOperation`으로
  교체했습니다. 메인 메뉴만 확인하는 부팅 스모크로는 드러나지 않는
  결함이었습니다.

## 2.0.0

### 기반과 사용자 경험

- 이벤트 버스, 송수신 패킷 이벤트, 공용 `B2TApi`와 로컬 Fabric 애드온
  진입점을 추가했습니다.
- 회전, 입력, 핫바 슬롯과 인벤토리 작업의 충돌을 조정하는
  `ActionCoordinator`를 추가했습니다.
- 문자열, 문자열 목록, 색상 설정과 모듈 위험도·즐겨찾기를 추가했습니다.
- 설정 포맷 v2에 프로필, 서버별 프로필 연결, 친구와 차원별 웨이포인트를
  추가하고 v1 자동 이관을 지원합니다.
- `;b2t` 로컬 명령어, 검색·즐겨찾기·위험도 표시가 있는 ClickGUI, 드래그
  가능한 HUD 편집기와 알림 시스템을 추가했습니다.
- Baritone, ViaFabricPlus와 Sodium의 설치 여부를 감지하는 선택적 통합 기반을
  추가했습니다. 해당 모드를 번들하거나 다운로드하지 않습니다.

### 모듈

- 전투 영역에 Offhand, Auto Crystal/Mine, Kill Aura, Criticals, Surround,
  Hole Fill, Self/Auto Trap, Burrow, Anchor/Bed Aura, Bow Aim, Quiver,
  City Breaker와 Piston Crystal 등을 추가했습니다.
- 월드 렌더링 영역에 Player/Storage/Block/Hole ESP, Tracers, Nametags,
  Trajectories, Waypoints, New Chunks, Logout Spots, Freecam, XRay, Chams,
  Stash Finder와 Portal Coords 등을 추가했습니다.
- 이동 영역에 Safe Walk, Auto Center, Hole Snap, Step, No Fall, Fast Swim,
  Jesus, Elytra Swap/Control, Ground Speed, No Slow와 No Rotate 등을
  추가했습니다.
- 유틸리티 영역에 Replenish, Auto Respawn/Reconnect/Mend, Anti AFK,
  Chest Swap, Fast Use, Inventory Manager와 Auto Craft 등을 추가했습니다.
- HUD에 활성 모듈, TPS/RTT, 대상, 서버/큐, 로컬 토템 사용 추정 표시를
  추가했습니다.
- 전투·이동·자동화 모듈은 기본 비활성화하고 친구 제외 및 동작 충돌 방지
  경로를 적용했습니다.

### 테스트, 보안과 배포

- 기반 서비스, 설정 v1→v2 이관, 프로필, 친구, 웨이포인트와 액션 중재 단위
  테스트를 확장했습니다.
- GUI, 명령어·서비스, 설정 복구와 월드 모듈을 다루는 격리형 Fabric Client
  GameTest 시나리오를 확장했습니다.
- 프로덕션 코드의 외부 네트워크, 프로세스 실행, 런처 계정과 토큰 API 표식을
  차단하는 로컬 전용 정책을 유지했습니다.
- 아카이브 타임스탬프 제거와 파일 순서 고정, CycloneDX 형식의 로컬 런타임
  의존성 목록, SHA-256 목록과 감사 가능한 `releaseBundle`을 추가했습니다.
- 반복 성능 불변조건 테스트와 체크섬·SBOM·선택적 반복 빌드를 확인하는
  `performance-soak.sh`, `verify-release.sh`를 추가했습니다.
- 배포 파일명을 Minecraft 대상이 드러나는
  `b2t-client-mc1.21.4-2.0.0.jar`로 변경했습니다.
- Java 25 기반 Minecraft 26.2 어댑터와 fail-closed 카탈로그 기반을
  추가했습니다. 2.1.0에서 공통 90개 카탈로그의 플랫폼 구현을 완성했습니다.
- 두 플랫폼의 재현 JAR, sources, SBOM과 체크섬을 검증하는
  `multiVersionBuild`·`multiVersionRelease` 작업을 추가했습니다.

## 1.0.0

- 반응형 카테고리·스크롤 ClickGUI와 31개 모듈을 제공했습니다.
- Auto Totem/Armor/Tool/Weapon, Trigger Bot과 생존·상태 HUD를 추가했습니다.
- 모듈 생명주기 오류 격리, 종료 시 상태 복구와 설정 손상 복구를 추가했습니다.
- 단위·정책 테스트, 격리된 Fabric Client GameTest, `e2eTest`와
  `qualityGate` 작업을 추가했습니다.
- HUD와 ClickGUI의 모듈 목록 할당, 렌더 빈도 계산, 인벤토리 반복 탐색과
  키 입력 상태 저장을 최적화했습니다.
- Minecraft Java 1.21.4 / Java 21로 고정했습니다.
